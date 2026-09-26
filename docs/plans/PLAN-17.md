# PLAN-17: Docker 샌드박스 — 격리 실행 능력

**이슈**: [#17](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/17)
**타입**: feature
**작성일**: 2026-09-26 (rev.3 — 재검토 반영 · 신규 중대 3건. rev.2 는 1차 검토의 중대 6건 반영)

---

## 0. 이 이슈가 무엇인가

**S-3 의 실행체다.** 안전 경계 6조 중 **아직 구현이 0인 유일한 조항**이고, 이 PR 이 그것을
코드로 만든다. 커버리지 100% 대상이다.

우리가 clone·build·test 하는 것은 **신뢰할 수 없는 코드**다. Gradle/Maven 빌드 스크립트는
임의 코드 실행이고, 대상 저장소는 우리가 통제하지 않는다.

### 계약 표면

```
agent/domain
  CodeSandbox          .run(SandboxCommand) → SandboxResult

  SandboxCommand       🔴 sealed — 세 가지만 존재한다. 각각이 컨테이너 설정 하나에 대응한다
    ├ WarmCommand      네트워크 O · 명령은 우리 것 · 🔴 캐시 볼륨 **미마운트**
    ├ SeedCacheCommand 네트워크 X · 명령은 우리 것(cp) · 캐시 RW · 워크스페이스 RO
    └ ExecuteCommand   네트워크 X · 명령은 대상 것 · 캐시 RO

  SandboxWorkspace     검증된 호스트 경로 (루트 하위임이 보장된 값)
  SandboxLimits        cpu · memory · pids · timeout — 전부 필수
  BuildTool            GRADLE | MAVEN
  SandboxImages        javaVersion → 이미지 (화이트리스트)
  SandboxResult        종료코드 · 출력(잘림 표시) · 소요 · 타임아웃 · cleanedUp
        ▲
agent/adapter/out/sandbox
  DockerCodeSandbox        수명주기 조율                    @ExternalAdapter
  SandboxContainerSpec     🔴 격리 설정을 만드는 유일한 자리 (순수 함수 · sealed switch 망라)
  ContainerOperations      docker 제어 6개만 노출하는 좁은 이음매
  DockerContainerOperations  docker-java 구현 (얇은 경유층)   @ExternalAdapter
  SandboxProperties
config/
  SandboxConfig            @ExternalAdapter — DockerClient 빈
```

⚠️ `agent` 는 **`RepositoryPolicy` 를 import 하지 않는다**(규율 ④). 필요한 것은
`javaVersion`·`buildTool`·명령 같은 **값**이고, 변환은 호출자(#18·#19)의 몫이다.

---

## 1. 요구사항

| # | 완료조건 | 증거 |
|---|---|---|
| FR-1 | `CodeSandbox` 선언 · `DockerCodeSandbox` 구현 | Stage 1·3 |
| FR-2 | **호스트 실행 경로를 만들지 않는다** | ⚠️ §2 — 증거의 한계를 적는다 |
| FR-3 | `/var/run/docker.sock` 마운트 금지 | 바인드 전수 테스트 |
| FR-4 | 우리 시크릿 환경변수를 전달하지 않는다 | 단계별 화이트리스트 테스트 |
| FR-5 | 실행마다 새 컨테이너 · **끝나면 삭제** | 기록 대역 |
| FR-6 | 타임아웃 시 강제 종료 + **정리 결과 로그** | 기록 대역 (순서 단언) |
| FR-7 | Java 버전·빌드 명령으로 이미지·명령 선택 | ⚠️ **부분 완료** — §7 |

---

## 2. 게이트 판정

### 🔴 S-3 — 조항 1:1 대조

| S-3 요구 | 이 PR | rev.1 대비 |
|---|---|---|
| 네트워크 `none` | **sealed 타입** — `ExecuteCommand` 에 네트워크 인자가 없다 | 🔺 rev.1 은 호출자가 enum 을 골랐다 |
| CPU·메모리·**실행시간**·PID 상한 | `SandboxLimits` 필수. **하나라도 없으면 거부** | 🔺 rev.1 은 실행시간이 거부 대상에서 빠졌다 |
| 파일시스템 — 작업 디렉토리만 | **경로를 정규화해 루트 하위임을 단언** | 🔺 rev.1 은 바인드 **개수**만 셌다 |
| 호스트 볼륨·소켓 금지 | 바인드 전수 검사 · 소켓 문자열 부재 | — |
| `ProcessBuilder`·`Runtime.exec` 금지 | 소스 스캔 테스트 + **한계 명시** | 🔺 rev.1 은 「쓰지 않는다」로 끝냈다 |
| 시크릿 환경변수 금지 | 단계별 **화이트리스트** | 🔺 rev.1 의 「고정 3개」는 사실이 아니었다 |

#### 🔴 C-3 — 네트워크를 「고를 수 있게」 두면 증명할 것이 없다

rev.1 은 `SandboxCommand` 가 `SandboxNetwork` 를 필드로 받았다. 그러면
**`EGRESS` + 대상 저장소 명령**이 타입상 합법이고, 그것은 계획서 스스로 「임의 네트워크
행위를 대신 해 주는 셈」이라 규정한 상태다. 테스트 「워밍에서만 열린다」가 단언할 대상이
없었다 — 기껏해야 「기본값이 NONE」이다.

**생성 경로를 가른다.**

```java
public sealed interface SandboxCommand
        permits WarmCommand, SeedCacheCommand, ExecuteCommand { … }

// 명령 인자가 없다 — 무엇을 돌릴지 우리가 정한다. 캐시 볼륨을 마운트하지 않는다
WarmCommand.of(workspace, buildTool, javaVersion, limits)

// 명령도 네트워크도 인자가 아니다 — 우리 cp 를, 네트워크 없이 돌린다
SeedCacheCommand.of(workspace, cacheVolume, limits)

// 네트워크 인자가 없다 — 언제나 none 이다
ExecuteCommand.of(workspace, buildTool, javaVersion, argv, limits)
```

「EGRESS + 대상 명령」이 **표현 불가능**해진다. S-2 의 「draft 플래그를 두면 언젠가 켜진다」와
같은 논리다.

#### 🔴 C-4 — `SANDBOX_NETWORK` 를 코드가 읽지 않는다

`safety-boundaries.md` S-3 표는 `SANDBOX_NETWORK=none` 을 환경변수로 적어 뒀다.
**그대로 두면 실행 단계 격리가 배포 설정 한 줄로 뒤집힌다.**

> **결정** — `sandbox.network` 라는 설정 키를 만들지 않는다. 네트워크는 명령 타입이 정한다.
> `.env.example` 에서 `SANDBOX_NETWORK` 를 제거하고 `safety-boundaries.md` S-3 표를
> **같은 커밋에서** 정정한다. 문서와 코드가 서로 다른 방어를 가리키게 두지 않는다.

#### 🔴 C-5 — 바인드는 개수가 아니라 **대상**을 봐야 한다

`workspace` 는 호출자가 주는 호스트 경로다. 검증이 없으면 —

| 값 | 결과 |
|---|---|
| `~/` | 신뢰할 수 없는 코드가 **홈을 RW 로** 잡는다 — `.env` · `~/.gradle/gradle.properties` · `~/.ssh` · `~/.docker/config.json` (전부 S-4 대상) |
| `<root>/../..` · 심볼릭 링크 | 같은 결과 |

**`SandboxWorkspace` 를 값 타입으로 만들고, 생성 시점에 정규화(`toRealPath`) 후
`sandbox.workspace-root` 하위임을 단언**한다. 검증되지 않은 `Path` 로는 컨테이너를 만들 수 없다.

S-1 의 「push 직전 owner 어설션」과 **정확히 같은 성격**의 어설션이다. 없으면 이 PR 의
S-3 방어가 호출자 신뢰에 기댄다.

#### ⚠️ C-6 — FR-2 의 증거는 약하다. 약하다고 적는다

「호스트 실행 경로를 만들지 않는다」의 현재 증거는 `safety-boundary-check.sh` 의 문자열
grep 이고, `safety-boundaries.md` 스스로 「훅이 문자열만 본다 → 훅 통과가 합격이 아니다」라고
적어 뒀다.

보강 — **소스 스캔 테스트**를 둔다(`HostExecutionAbsenceTest`). `src/main/java` 전체에서
`ProcessBuilder`·`Runtime.getRuntime().exec` 참조가 없음을 단언한다. 훅과 중복이지만
`--no-verify` 로 건너뛸 수 없는 자리에 하나 더 둔다.

🔴 **그래도 한계가 남는다** — 리플렉션·서비스로더로 우회하면 둘 다 못 본다.
**「구조적으로 불가능하다」고 적지 않는다.** 판단은 리뷰가 한다.

#### 🔴 FR-4 — 환경변수는 「고정 3개」가 아니라 단계별 화이트리스트다

rev.1 은 「어댑터가 계산한 고정 3개」라고 적었는데 **사실이 아니다.** 아래처럼 단계마다 다르다.

| | 워밍 | 씨딩 | 실행 |
|---|---|---|---|
| `GRADLE_USER_HOME` | `<workspace>/.gradle` | — | `<workspace>/.gradle` |
| `GRADLE_RO_DEP_CACHE` | — | — | `/cache` |

**호출자가 환경변수를 추가할 통로는 없다** — `SandboxCommand` 의 세 구현 어디에도 그 필드가
없다. 이 사실을 **리플렉션 테스트로 확인하지 않는다**(우회가 쉽고, 손수 짠 리플렉션을 쓰지
않는다는 것이 이 저장소의 방침이다). record 컴포넌트가 계약이고, 바뀌면 리뷰에 보인다.

대신 테스트는 **실제로 만들어진 컨테이너 설정에 화이트리스트 밖 변수가 없는가**를 본다 —
그것이 진짜 지켜야 할 성질이다.

### 🔴 S-4 — 대상 저장소 텍스트가 우리 코드의 입력이다

`RepositoryPolicy.javaVersion`·`buildCommand` 는 **LLM 이 대상 저장소 문서에서 뽑은 값**(#7)이다.

| 위험 | 막는 법 |
|---|---|
| 🔴 **이미지 이름 주입** — `javaVersion` 이 이미지 좌표가 되면 공격자 레지스트리 이미지를 pull·실행한다 | `SandboxImages` **화이트리스트**. 문자열 조립 경로 없음. 모르면 기본 이미지 + **WARN** |
| 🔴 **워밍 명령 주입** — 워밍은 네트워크가 열려 있다 | `WarmCommand`·`SeedCacheCommand` 에 **명령 인자가 없다** (C-3) |
| 🔴 **쉘 주입** — `buildCommand` 를 `sh -c "<문자열>"` 로 돌리면 `;`·`&&`·`$(…)` 가 산다 | 🔴 **명령은 `List<String>` argv 다. 쉘을 경유하지 않는다.** 컨테이너 `Cmd` 에 argv 로 넘긴다 |

argv 로 두는 것이 「`FOO=bar cmd` 로 환경변수를 넣는다」도 함께 막는다 — 쉘이 없으면
그 문법이 해석되지 않는다.

⚠️ 로그에 대상 저장소 문자열을 **포맷 문자열로** 쓰지 않는다(로그 인젝션).

### 다른 조항

| 조항 | 판정 |
|---|---|
| S-1 · S-2 | **미접촉** — push·PR·Fork 경로가 없다 |
| S-5 | 🔵 인접 — 값을 쓰되 엔티티를 읽지 않는다 |
| S-6 | 🔵 인접 — 샌드박스는 **상태를 전이시키지 않는다.** 재시도 상한은 #21 |

### 미결 대조

| Q | 판정 |
|---|---|
| **Q-4** | 🔴 **부분 확정** — §3. 「닫는다」고 적지 않는다 |
| **Q-9b** | 🔴 접촉 — `withApiVersion` 명시. §4 |
| Q-10 | 🔵 접촉 — 이 PR 의 자동 테스트는 **Docker 를 쓰지 않는다.** CI 영향 없음 |
| Q-3 · Q-6 | 🔵 인접 — 여기서 정하지 않는다 |

---

## 3. 🔴 Q-4 — 방향은 확정, 메커니즘은 실측 조건부

**사용자 결정 (2026-09-26)**: 2단계 실행 + 읽기전용 의존성 캐시.

### 🔴 rev.2 의 메커니즘도 성립하지 않았다 — 워밍 홈을 볼륨에 두면 안 된다

rev.2 는 워밍을 `GRADLE_USER_HOME=/cache`(볼륨)로 돌렸다. **두 군데서 무너진다.**

| 무너지는 곳 | 왜 |
|---|---|
| 🔴 **wrapper 배포본** | 한 프로세스에 `GRADLE_USER_HOME` 은 **하나**다. 워밍 홈이 `/cache` 면 wrapper 는 **정의상** `/cache/wrapper/dists` 에 간다. 실행 단계의 새 홈(`<workspace>/.gradle`)에는 없고, wrapper 부트스트랩은 **Gradle 이 시작되기 전**이라 `--offline` 도 못 막는다 → 실행 첫 명령이 100% 죽는다 |
| 🔴 **볼륨이 임의 쓰기 표면이 된다** | `GRADLE_USER_HOME` 은 의존성 캐시만 있는 곳이 아니다. 워밍에서 도는 **신뢰할 수 없는 빌드 스크립트**가 `/cache/init.d/*.gradle` 을 심으면, 그 홈을 쓰는 **모든 다음 워밍에서 자동 실행**된다 — 네트워크가 열린 채로. 볼륨은 #26 까지 지우지 않으므로 **지속된다** |

두 번째가 특히 나쁘다 — 「워밍 명령은 우리가 정한다」(C-3 의 핵심 방어)가 **볼륨을 통해
우회**된다. rev.2 가 적은 「캐시에 들어오는 것은 좌표+해시로 식별되는 아티팩트뿐」은
**과대 주장이었다.**

### ✅ 올바른 구조 — 워밍 홈을 워크스페이스에 두고, `modules-2` 만 볼륨으로 옮긴다

```
① 워밍     GRADLE_USER_HOME=<workspace>/.gradle
           네트워크 O · 🔴 캐시 볼륨 미마운트 · 명령은 우리 것
                       ↓  워크스페이스에 caches/modules-2 와 wrapper/dists 가 쌓인다
② 씨딩     명령: ["cp","-a","<workspace>/.gradle/caches/modules-2","/cache/modules-2"]
           네트워크 none · 캐시 볼륨 RW · 워크스페이스 RO · 🔴 대상 코드를 돌리지 않는다
                       ↓  볼륨에는 modules-2 하나만 들어간다
③ 실행     GRADLE_RO_DEP_CACHE=/cache            (볼륨 RO · 네트워크 none)
           GRADLE_USER_HOME=<workspace>/.gradle  (RW — wrapper·락·transform 이 여기 있다)
```

**이 구조가 세 문제를 한꺼번에 닫는다.**

| | 어떻게 |
|---|---|
| wrapper | 워밍과 실행이 **같은 워크스페이스·같은 홈**을 쓴다. 워밍이 받아 둔 배포본이 그대로 있다 |
| 볼륨 오염 | 볼륨에 쓰는 것은 **②뿐**이고, ②는 **우리 `cp` 를 네트워크 없이** 돌린다. 신뢰할 수 없는 코드가 볼륨에 닿는 경로가 **없다** |
| 레이아웃 | `GRADLE_RO_DEP_CACHE` 는 `modules-2` 를 **담고 있는** 디렉토리를 가리킨다. 볼륨 루트가 곧 그것이다 |

⚠️ **②가 쉘을 경유하지 않는다.** `cp` 는 argv 로 넘기는 우리 명령이고 기본 이미지에 있다.
「명령은 argv · 쉘 미경유」 규칙의 예외가 아니다.

⚠️ 그래서 `SandboxCommand` 가 **3종**이다(§0). 각 타입이 컨테이너 설정 하나에 대응하고,
`SandboxContainerSpec` 이 sealed **switch 망라성**으로 분기한다 — 네 번째 타입이 생기면
컴파일이 막는다.

⚠️ **씨딩과 소비의 Gradle 버전이 같아야 한다.** `modules-2` 아래가 버전별 디렉토리다.
워밍과 실행이 같은 워크스페이스의 같은 wrapper 를 쓰므로 자동으로 만족된다 — 그것이
이 구조의 또 다른 이득이다.

### 왜 RO 가 설계의 핵심인가

캐시 공유는 **「상태 재사용」**이고 `external-deps.md` 가 금지한 것이다 — 「앞 실행의
산출물이 다음 판정을 오염시킨다」. **읽기전용이면 그 경로가 닫힌다.** 실행 단계가 만든
것은 워크스페이스에만 쌓인다.

### 🔴 실측 없이 「닫았다」고 하지 않는다

RO 공유 캐시는 **modules 캐시만** 덮는다. 아래가 남는다.

| 덮이지 않는 것 | 증상 | 대응 |
|---|---|---|
| ~~wrapper 배포본~~ | | ✅ 위 구조가 닫았다 |
| **toolchain 자동 프로비저닝** | 빌드가 요구하는 JDK 가 이미지와 다르면 네트워크를 탄다 | 이미지를 `javaVersion` 으로 맞춘다(FR-7). 어긋나면 실패 |
| **dynamic version · SNAPSHOT** | RO 캐시로 해결되지 않는다. `spring-kafka` 가 Spring SNAPSHOT 저장소를 참조할 수 있다 | **실측 대상** |
| **`--offline` 부재** | network=none 에서 해석 시도는 실패가 아니라 **DNS/connect 타임아웃**이다. 30분 예산을 조용히 태운다 | 아래 |

#### `--offline` 부착 규칙 — 「명령은 대상 것」의 유일한 예외

실행 단계에 `--offline` 을 우리가 덧붙인다. **argv[0] 이 `./gradlew`·`gradle` 일 때만**
붙인다 — `bash ci/build.sh` 같은 명령에 붙이면 뜻이 깨진다.

덧붙이는 근거 — 붙이지 않으면 네트워크 부재가 **즉시 실패가 아니라 타임아웃**으로 나타나
30분 예산을 태우고, 결과가 「테스트 실패」로 오분류된다. 실패의 **종류**를 보존하기 위한 것이다.

> **Q-4 는 「Gradle 한정 · 방향 확정 · 실측 미완」으로 `open-questions.md` 에 적는다.**
> 「✅ 확정」으로 닫으면 Maven 대상(Phase 2 `spring-boot`)에서 다시 열 때 근거가 사라진다.
> 종결 조건: **`spring-kafka` 로 워밍 → 씨딩 → 오프라인 실행을 한 번 통과시킨다.**
> 워크스페이스를 채우는 주체(#18)가 있어야 가능하다 — 🔴 **#18 이슈에 이 실측 항목을
> 코멘트로 남긴다**(Stage 6). 계획서에만 적으면 이슈 이동 중 증발한다.

### 🔴 잔여 위험 — 밖으로 나가는 것만이 아니다

**워밍 단계는 임의 코드 실행이다.** 네트워크가 열린 컨테이너에서 대상 저장소의 빌드 도구가 돈다.

- **밖으로** — 트래픽을 막지 못한다. 미러 프록시는 MVP 범위 밖
- 🔴 **안으로** — 기본 `bridge` 에서 컨테이너는 **호스트 게이트웨이**로 나갈 수 있다.
  호스트에 퍼블리시된 우리 PostgreSQL(5432)·Redis·앱 포트가 노출된다.
  → **전용 네트워크**를 쓴다. ⚠️ 완전 차단이 아니라 **줄이는 조치**다
- **워크스페이스 안** — 워밍이 `<workspace>/.gradle/init.d` 를 심으면 **같은 워크스페이스의
  실행 단계**에서 돈다. 다만 실행 단계는 네트워크가 없고, 워크스페이스는 후보 1건의
  수명과 함께 버려진다(#18). **볼륨처럼 지속되지 않는다** — 그것이 rev.2 와의 결정적 차이다

#### 🔴 전용 네트워크 이름을 자유 문자열로 받지 않는다

`sandbox.warm-network` 를 그대로 `networkMode` 에 넣으면 **C-4 가 뒷문으로 돌아온다.**

| 값 | 결과 |
|---|---|
| `host` | 컨테이너가 **호스트 네트워크 네임스페이스를 공유**한다. bridge 보다 훨씬 심각하다 |
| `container:<id>` | 다른 컨테이너의 네임스페이스에 붙는다 |

→ **이름 형식을 검증**하고(`[a-z0-9][a-z0-9_.-]*`), `host`·`none`·`bridge`·`container` 접두를
**거부**한다. 어댑터가 그 이름의 네트워크를 **없으면 만든다.** 테스트로 고정한다 —
「bridge 가 아님」만 보는 테스트는 `host` 를 통과시킨다.

### 캐시 볼륨의 범위와 동시성

| 항목 | 결정 |
|---|---|
| 이름 | **저장소별** — `oss-agent-cache-<owner>-<name>` |
| 🔴 이름 검증 | Docker 는 마운트 소스가 **경로 형태면 볼륨이 아니라 호스트 경로 바인드**로 해석한다. `owner/name` 은 대상 저장소 좌표(외부 입력)이므로 **`SandboxImages` 와 같은 등급으로 화이트리스트 검증 + 테스트**한다 |
| 왜 전역이 아닌가 | 전역이면 저장소 A 의 워밍이 B 의 판정 입력을 바꾼다. 재현성이 깨진다 |
| 동시성 | **저장소당 워밍 1건.** 프로세스 내 락으로 막는다. ⚠️ 다중 워커에서는 성립하지 않는다 — Q-3 과 함께 `open-questions.md` 에 적는다 |
| 수명 | 이 PR 은 **지우지 않는다.** #26 의 몫. ⚠️ 저장소별 × Phase 2 (7개)면 디스크 증가가 빠르다 — 대략 저장소당 수백 MB~수 GB 로 전달한다 |

### ⚠️ Maven 은 동등물이 없다

Maven 로컬 저장소는 읽기전용으로 못 쓴다(`_remote.repositories` 를 쓴다).
**`BuildTool.MAVEN` 은 지원하지 않는다고 실패시킨다.** 조용히 네트워크를 열지 않는다 —
그것이 최악이다. Phase 1 대상 `spring-kafka` 는 Gradle 이다.

---

## 4. 기술 설계

### 왜 docker-java 인가 (Q-11 — 경계마다 근거를 다시 본다)

| 선택지 | 판정 |
|---|---|
| `ProcessBuilder` + docker CLI | ❌ S-3 훅이 막는다. **S-3 의 실행체를 만들며 S-3 가드를 `safety-ok` 로 우회하는 것은 앞뒤가 맞지 않는다.** CLI 인자 조립은 주입면도 넓다 |
| Testcontainers (main 의존) | ❌ 테스트 라이브러리. Ryuk·reuse 가 「실행마다 새로·끝나면 삭제」와 싸운다 |
| unix 소켓 raw HTTP | ❌ JDK `HttpClient` 가 유닉스 도메인 소켓을 지원하지 않는다 |
| **docker-java** | ✅ **채택** |

결정적 근거 — **이 저장소가 이미 그렇게 가정하고 있다.** `ExternalAdapters` 의
`NETWORK_CLIENTS` 에 `com.github.dockerjava.api.DockerClient` 가, javadoc 예시에
`DockerCodeSandbox` 가 이미 적혀 있다. 가드가 물 준비를 해 둔 타입을 쓰는 것이 맞다.

**Stage 0 확인 완료 (2026-09-26)** — `docker-java-core` + `transport-zerodep` 3.4.2 해석 성공.
⚠️ 전이 의존이 낡아 **올려 고정했다**: `guava 19.0(2016) → 33.4.8-jre`(CVE-2018-10237 ·
CVE-2020-8908), `commons-compress 1.21 → 1.27.1`(CVE-2024-25710 · CVE-2024-26308).
의존성을 들이는 것과 그 전이 의존을 방치하는 것은 별개다.

**API 표면 확인 완료** — `withNetworkMode`·`withBinds`·`withCpuQuota`·`withMemory`·
`withMemorySwap`·`withPidsLimit`·`withCapDrop`·`withSecurityOpts`·`withPrivileged` 전부 존재.

### 🔴 격리 설정을 만드는 자리를 하나로 둔다

```java
// SandboxContainerSpec — 순수 함수. Docker 타입을 만들 뿐 호출하지 않는다
static HostConfig hostConfig(SandboxCommand command, SandboxProperties props)
```

**S-3 의 불변식이 전부 여기 있다.** 흩어지면 「어느 경로로는 네트워크가 열린다」가 생긴다.
한 자리에 모으면 **Docker 없이 유닛 테스트로 전수 검증**할 수 있고, 그것이 커버리지 100% 를
실제로 달성하는 방법이다.

| 설정 | 값 | 왜 |
|---|---|---|
| `networkMode` | `none` / 워밍만 전용 네트워크 | S-3 · C-3 |
| `binds` | **검증된** 워크스페이스 RW + 캐시(RW\|RO). 그 외 없음 | S-3 · C-5 |
| `cpuQuota`·`cpuPeriod`·`memory` | 설정값. **null 이면 거부** | 「기본값에 맡기면」 제한이 없는 것과 같다 |
| 🔴 `memorySwap` | **`memory` 와 같은 값** | 안 주면 스왑으로 **선언한 상한의 2배**까지 쓴다. 상한이 상한이 아니게 된다 |
| `pidsLimit` | 설정값 | fork 폭탄. CPU·메모리만으로는 못 막는다 |
| `privileged` | `false` | |
| `capDrop` | `ALL` | 빌드에 커널 권한이 필요 없다 |
| `securityOpts` | `no-new-privileges` | setuid 승격 차단 |
| 환경변수 | 단계별 화이트리스트 | FR-4 |

⚠️ **`autoRemove` 를 쓰지 않는다.** 종료와 동시에 지워져 **로그를 못 읽는다** — FR-6(정리
결과 로그)과 정면으로 충돌한다. 삭제는 `finally` 에서 명시적으로 한다.

⚠️ **`readonlyRootfs` 를 켜지 않는다.** 빌드가 `/tmp` 에 쓴다. 대신 워크스페이스 밖은
컨테이너와 함께 사라진다. 켜지 않은 이유를 코드에 남긴다.

⚠️ **비 root 실행은 하지 않는다.** 바인드 소유권이 호스트 uid 와 엮이고 macOS·Linux 가
다르다. 컨테이너 안 root 는 컨테이너 안에서만 root 이고 `capDrop ALL` +
`no-new-privileges` 가 걸려 있다. **알려진 공백**으로 남기고 근거를 코드에 적는다.

### 수명주기

```
create → start → wait(timeout) ─┬─ 정상 → 로그 수집(상한·타임아웃)
                                └─ 초과 → kill
                                          ↓
                    finally: remove(force) · 실패하면 결과와 로그에 남긴다
```

🔴 **`finally` 로도 부족하다.** 제거가 실패할 수 있고(데몬 장애), 조용히 넘기면 컨테이너가
쌓인다. `SandboxResult.cleanedUp` 으로 호출자에게 알리고 `WARN` 을 남긴다.

⚠️ 회수되지 않은 컨테이너는 **라벨**(`oss-agent.sandbox=true` + `oss-agent.instance=<식별자>`)을 달아 두어 나중에
일괄 정리할 수 있게 한다. 기동 시 자동 정리는 **이 PR 에서 하지 않는다**(다중 인스턴스에서
남의 컨테이너를 지울 수 있다) — 라벨만 남기고 정리는 #26. ⚠️ **인스턴스 식별자를 함께 남기지 않으면 #26 이 같은 딜레마를 다시 만난다**.

### 좁은 이음매 — `ContainerOperations`

`testing-philosophy.md` 가 못 박았다 — 「**「타임아웃 시 컨테이너가 정리된다」는 능력
페이크로 증명되지 않는다** — 페이크는 컨테이너를 만들지 않기 때문이다. 층을 하나 더 두는
이유가 이것이다」.

docker 제어를 **6개**(`create`·`start`·`await`·`logs`·`kill`·`remove`)만 노출하고,
테스트는 **호출을 기록하는 대역**으로 수명주기를 검증한다. `DockerClient` 를 직접 목으로
만들면 fluent cmd 체인 때문에 테스트가 구현 세부에 묶인다.

### 출력 — 받은 뒤 자르지 않는다

🔴 수백 MB 로그를 메모리에 모은 뒤 자르면 **우리 프로세스가 죽는다.** 상한에 닿으면
**더 쌓지 않는다**(메모리 유계). 로그 수집 자체에도 타임아웃을 둔다(kill 후 조회가 매달릴 수 있다).

⚠️ **스트림 자체를 끊지는 않는다** (구현 중 확정). 끊으려면 콜백 안에서 docker-java 의
`onComplete()` 를 불러야 하는데 그것이 내부적으로 `close()` 라, **리더 스레드가 자기 스트림을
닫는** 꼴이 되고 거기서 난 `IOException` 이 `awaitCompletion` 에서 다시 튀어나온다 —
**정상적인 절단마다 경고와 스택트레이스가 찍힌다.** 시간은 타임아웃이 이미 묶고 있으므로,
검증하지 못한 동작으로 조금 더 빨리 끊는 것보다 이쪽이 낫다.
🔴 **로그 수집이 매달리면 `finally` 의 `remove` 에 도달하지 못해 FR-5·FR-6 이 동시에 깨진다.**
kill 직후 조회가 매달리는 경우가 실제로 있다 — `sandbox.log-timeout` 으로 끊는다.
`truncated` 를 결과에 담아 **자른 사실을 숨기지 않는다** — 잘린 로그를 LLM 이 「전부」로 읽고
「테스트가 통과했다」로 판단하면 게이트가 무력해진다.

### 설정 — 기존 축과 섞지 않는다

| 키 | 뜻 |
|---|---|
| `sandbox.timeout` | 🔴 **실행 1회의 상한.** `agent.execution.timeout-seconds`(파이프라인 예산)와 **다른 축**이다 |
| `sandbox.warm-timeout` | 워밍은 짧게 |
| `sandbox.log-timeout` | 🔴 로그 수집의 상한. 아래 |
| `sandbox.workspace-root` | C-5 의 루트. **절대경로·존재·디렉토리를 기동 시 검증**한다 |
| `sandbox.warm-network` | 전용 네트워크 이름. **형식 검증 + `host`·`container` 거부** (§3) |
| `sandbox.docker.api-version` | Q-9b. **핀이지 고정이 아니다** — 데몬이 더 낮으면 내려야 한다 |
| `sandbox.max-output-chars` · `sandbox.pids-limit` · cpu · memory | |

#### 🔴 `Duration` 단위 함정을 두 겹으로 막는다

`application.yml` 에 기록이 남아 있다 — `timeout-seconds` 로 두면 `Duration` 에 바인딩되지
않아 **값을 낮춰도 아무 일도 일어나지 않는다**(#10). 여기는 **반대 방향 함정이 하나 더** 있다:

> `sandbox.timeout: ${SANDBOX_TIMEOUT_SECONDS:1800}` 으로 쓰면 단위 없는 숫자가
> **밀리초**로 해석돼 **1800 = 1.8초**가 된다. 모든 실행이 즉시 타임아웃한다.

→ `@DurationUnit(ChronoUnit.SECONDS)` 를 명시하고, `SandboxPropertiesTest` 가
**실제 `application.yml` 을 바인딩해 30분인지 단언**한다. 「바인딩 테스트가 있다」로는
부족하고 **단위를 겨냥한 단언**이 있어야 한다.

#### 🔴 `workspace-root` 자체의 방어

미설정·빈 문자열·상대경로면 **「모든 경로가 루트 하위」가 되어 C-5 가 통째로 무력해진다.**
`SandboxLimits` 와 같은 등급으로 **없으면 거부**에 포함시킨다.

### 🔴 `.env.example` — 「필요 시」가 아니라 확정 작업이다

S-3 표가 이미 `SANDBOX_*` 5개를 가리킨다. 새 키가 들어가면서 매핑을 정하지 않으면
**안전 경계 문서가 존재하지 않는 변수를 가리키게 된다.**

| 기존 | 처리 |
|---|---|
| `SANDBOX_NETWORK` | 🔴 **제거** (C-4) |
| `SANDBOX_CPU_LIMIT`·`SANDBOX_MEMORY_LIMIT`·`SANDBOX_TIMEOUT_SECONDS`·`SANDBOX_DOCKER_IMAGE` | 유지. `sandbox.*` 가 이 이름으로 읽는다 |
| 신규 | `SANDBOX_WARM_TIMEOUT_SECONDS` · `SANDBOX_PIDS_LIMIT` · `SANDBOX_DOCKER_API_VERSION` · `SANDBOX_WORKSPACE_ROOT` · `SANDBOX_MAX_OUTPUT_CHARS` |

⚠️ 이 세션은 `.env.example` 을 **읽을 수 없다.** Stage 6 에서 사용자에게 현재 내용을 받아
중복 없이 반영하고, 받지 못하면 **그 사실을 PR 본문에 남긴다.**

### 생성 파일

```
agent/domain/     CodeSandbox · SandboxCommand(sealed) · WarmCommand · ExecuteCommand
                  SandboxWorkspace · SandboxLimits · BuildTool · SandboxImages
                  SandboxResult · SandboxException · SandboxTransientException
                  SandboxPermanentException
agent/adapter/out/sandbox/  DockerCodeSandbox · SandboxContainerSpec
                            ContainerOperations · DockerContainerOperations · SandboxProperties
config/           SandboxConfig
```

### 수정 파일

| 파일 | 변경 |
|---|---|
| `libs.versions.toml`·`build.gradle.kts` | docker-java 2종 + 전이 의존 고정 ✅ **완료** |
| `application.yml` | `sandbox.*` |
| **`.env.example`** | 위 표 — `SANDBOX_NETWORK` 제거 포함 |
| **`safety-boundaries.md`** | 🔴 S-3 표에서 `SANDBOX_NETWORK` 제거 · 「네트워크는 명령 타입이 정한다」 |
| `external-deps.md` | Docker 샌드박스 절 — 2단계·RO 캐시·잔여 위험 |
| `open-questions.md` | **Q-4 부분 확정** · Q-9b 에 운영 코드 주의 |
| `codemaps/architecture.md`·`README.md`·`glossary.md` | 샌드박스 실재 반영 · 용어 |

### 테스트

| 테스트 | 무엇을 잡나 |
|---|---|
| `실행_명령에는_네트워크를_고를_자리가_없다_S3()` | C-3 — 타입 수준 |
| `실행_단계는_네트워크가_none_이다_S3()` | |
| `워밍은_전용_네트워크를_쓰고_기본_bridge_를_쓰지_않는다_S3()` | 호스트 서비스 도달 |
| 🔴 `전용_네트워크_이름으로_host_나_container_를_쓸_수_없다_S3()` | 「bridge 가 아님」만 보면 `host` 가 통과한다 |
| 🔴 `워밍은_캐시_볼륨을_마운트하지_않는다_S3()` | 신뢰할 수 없는 코드가 볼륨에 닿는 경로 부재 |
| 🔴 `씨딩은_네트워크가_없고_워크스페이스를_읽기전용으로_잡는다_S3()` | 볼륨에 쓰는 유일한 단계 |
| 🔴 `캐시_볼륨_이름이_호스트_경로가_되지_않는다_S3()` | 경로 형태면 볼륨이 아니라 바인드가 된다 |
| 🔴 `바인드_대상이_워크스페이스_루트_밖이면_거부한다_S3()` | C-5 — **개수가 아니라 대상** |
| `심볼릭_링크로_루트를_빠져나갈_수_없다_S3()` | C-5 |
| `바인드는_워크스페이스와_캐시_둘뿐이다_S3()` | 전수 |
| `docker_소켓을_마운트하지_않는다_S3()` | 바인드 경로 전수 |
| `실행_단계의_캐시는_읽기전용이다_S3()` | Q-4 의 핵심 |
| `CPU_메모리_PID_타임아웃_상한이_없으면_거부한다_S3()` | **타임아웃 포함** |
| `메모리_상한이_스왑으로_늘어나지_않는다_S3()` | `memorySwap == memory`. ⚠️ 스왑 계정을 지원하지 않는 커널에서는 Docker 가 **경고만 하고 무시**한다 — 이 테스트가 증명하는 것은 「스펙에 그렇게 실렸는가」까지다 |
| `컨테이너_환경변수가_단계별_화이트리스트_밖으로_나가지_않는다_S3_S4()` | FR-4 |
| `명령은_쉘을_경유하지_않는다_S4()` | argv — 주입면 |
| `정상_종료_후_컨테이너를_제거한다_S3()` | 기록 대역 |
| `타임아웃이면_kill_후_제거한다_S3()` | **순서까지** 단언 |
| `실행이_예외로_끝나도_제거한다_S3()` | `finally` |
| `제거_실패를_결과와_로그에_남긴다_S3()` | 조용히 넘기지 않는가 |
| `알_수_없는_java_버전은_기본_이미지로_가고_경고한다_S4()` | 이미지 주입 + 조용한 오분류 |
| `대상_저장소_문자열이_이미지_좌표가_되지_않는다_S4()` | 화이트리스트 |
| `Maven_은_지원하지_않는다고_실패한다()` | 조용히 EGRESS 로 새지 않는가 |
| `출력이_상한에_닿으면_끊고_사실을_남긴다()` | 힙을 태우지 않는다 |
| 🔴 `로그_수집이_매달려도_컨테이너를_제거한다_S3()` | FR-5·FR-6 이 동시에 깨지는 경로 |
| `대상_명령이_gradle_이_아니면_offline_을_붙이지_않는다()` | 부착 규칙 |
| `workspace_root_가_미설정이면_기동을_거부한다_S3()` | C-5 의 전제 |
| `HostExecutionAbsenceTest` | C-6 — 소스 스캔 |
| `SandboxPropertiesTest` | 실제 yml 바인딩 — 🔴 **타임아웃이 30분인지**(밀리초 오해석 아님) |

`FakeCodeSandbox`(`@FakeAdapter`) — **실패 모드를 재현할 수 있어야 한다**(타임아웃 · 0 아닌
종료코드 · 제거 실패). 「항상 성공하는 페이크」는 게이트를 검증하지 못한다.

⚠️ **커버리지 100% 의 예외를 명시한다** — `DockerContainerOperations` 는 docker-java 호출을
1:1로 옮기는 **얇은 경유층**이고 Docker 없이 검증할 수 없다. 로직을 두지 않는 것이 이 클래스의
계약이며, 그래서 미검증으로 둔다. 로직이 생기면 `DockerCodeSandbox` 로 올린다.

---

## 5. 구현 순서

| Stage | 내용 | 복잡도 |
|---|---|---|
| 0 | ✅ **완료** — docker-java 해석 · 전이 의존 고정 · API 표면 확인 | — |
| 1 | `agent/domain` — sealed 명령 · `SandboxWorkspace` 검증 · `SandboxImages` | 중간 |
| 2 | `SandboxContainerSpec` + 유닛 테스트 전수 | **높음** |
| 3 | `ContainerOperations` + `DockerCodeSandbox` 수명주기 | **높음** |
| 4 | `DockerContainerOperations` + `SandboxConfig` + 설정 + 바인딩 테스트 | 중간 |
| 5 | `FakeCodeSandbox` · 기록 대역 · `HostExecutionAbsenceTest` | 높음 |
| 6 | 문서 — **S-3 표 정정** · Q-4 부분 확정 · external-deps · `.env.example` · codemaps · **#18 에 실측 인계 코멘트** | 중간 |

---

## 6. 리스크

| 리스크 | 대응 |
|---|---|
| 🔴 워크스페이스가 홈·루트를 가리킨다 | 정규화 + 루트 하위 단언 (C-5) |
| 🔴 네트워크가 호출자 선택으로 열린다 | sealed 타입 (C-3) |
| 🔴 설정 한 줄로 격리가 꺼진다 | `SANDBOX_NETWORK` 제거 (C-4) |
| 🔴 이미지 이름 주입 | 화이트리스트 |
| 🔴 쉘 주입 | argv · 쉘 미경유 |
| 🔴 메모리 상한이 스왑으로 2배 | `memorySwap == memory` |
| 🔴 캐시 공유가 실행 간 오염 | 실행 단계 **RO** |
| 🔴 컨테이너 누수 | `finally` + 강제 제거 + `cleanedUp` + 라벨 |
| 🔴 **Q-4 메커니즘이 실측 전이다** | 「부분 확정」으로 적는다. wrapper·SNAPSHOT·`--offline` 이 미검증 |
| 워밍이 호스트 서비스에 닿는다 | 전용 네트워크. **완전 차단은 아니라고 적는다** |
| 출력이 힙을 태운다 | 스트리밍 절단 |
| `api-version` 핀이 낮은 데몬에서 거부 | 설정값 · 주석에 양방향 조정 의도 |
| 이미지가 로컬에 없으면 create 실패 | `SandboxTransientException` 으로 분류 (네트워크·레지스트리 사정) |
| FR-2 증거가 문자열 검사뿐 | **한계를 적는다.** 구조적 불가능이라 주장하지 않는다 |
| 🔴 워밍이 캐시 볼륨에 `init.d` 를 심어 다음 워밍에서 실행된다 | **워밍은 볼륨을 마운트하지 않는다.** 볼륨에 쓰는 것은 씨딩(우리 `cp` · 네트워크 없음)뿐 |
| 🔴 `warm-network` 에 `host` 가 들어간다 | 형식 검증 + 거부 테스트 |
| 🔴 `SANDBOX_TIMEOUT_SECONDS` 가 밀리초로 해석돼 1.8초가 된다 | `@DurationUnit(SECONDS)` + 값 단언 테스트 |
| 🔴 `workspace-root` 미설정으로 C-5 가 무력화 | 기동 시 거부 |
| 캐시 볼륨 이름이 호스트 경로 바인드로 해석된다 | 화이트리스트 + 테스트 |
| 로그 수집이 매달려 컨테이너가 남는다 | `log-timeout` + 테스트 |
| 이미지 pull 의 자격증명 관여 | 🔴 **우리가 pull 하지 않는다.** 운영이 미리 받아 둔다 — 없으면 `SandboxTransientException` |

---

## 7. 범위 밖 — 명시적으로 남긴다

- **워크스페이스를 채우는 것**(대상 저장소 clone) — #18. 🔴 단 **워밍·씨딩·실행이 같은
  워크스페이스를 써야 한다**는 제약을 계약에 못 박는다(§3). 안 그러면 「network=none 에서
  gradle wrapper 가 없다」로 깨진다
- 🔴 **Q-4 실측** — #18 이 워크스페이스를 채우면 `spring-kafka` 로 워밍→씨딩→오프라인 실행을
  한 번 통과시킨다. **#18 이슈에 코멘트로 남긴다**(Stage 6). 계획서에만 적으면 증발한다
- **`RepositoryPolicy` → `SandboxCommand` 변환** — #18·#19. 규율 ④
- **Maven** — 지원하지 않는다고 실패시킨다
- **비 root 컨테이너** — 바인드 소유권. 근거를 코드에 남긴다
- **미러 프록시** — 워밍 트래픽 통제. MVP 범위 밖
- **캐시 볼륨·누수 컨테이너 정리 정책** — #26
- **재시도** — #21
- **결과 영속화** — #18·#19

### ⚠️ 머지 시점의 실효 범위 — PR 본문에 싣는다

| | 머지 후 |
|---|---|
| `CodeSandbox` 능력 + Docker 구현 + S-3 불변식 | ✅ **동작** |
| FR-7 | ⚠️ **부분** — `javaVersion → 이미지` 화이트리스트는 동작. 「빌드 명령 선택」은 **값을 통과시킬 뿐** |
| 3단계 워밍·씨딩·실행 (Gradle) | ⚠️ **메커니즘 구현 · 실측 미완** — toolchain·SNAPSHOT·`--offline` 이 실제 저장소에서 서는지 확인되지 않았다 (#18) |
| Maven | ❌ **미지원** (실패시킨다) |
| 호출자 | ❌ **없다** — #18·#19 |

부르는 곳이 없다는 것과 실측이 안 됐다는 것을 숨기지 않는다.
