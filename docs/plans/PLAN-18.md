# PLAN-18: 코딩 에이전트 — 코드 수정·테스트 생성

**이슈**: [#18](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/18)
**type**: feature
**작성일**: 2026-09-27
**작성자**: smileboy0014

> **초안이 검토에서 blocker 5건을 받았다.** 이 문서는 그 반영본이고, §8 에 **무엇이 틀렸었는지**를 남긴다.

---

## 0. 한 PR 로 가되, **중단 규칙**을 함께 둔다

완료 조건이 **19개**이고 성격이 다른 넷이 섞여 있다.

| 덩어리 | 무엇 | 위험 |
|---|---|---|
| **A. 착수 게이트** | `POST /implement` · 통행증 · 후보→저장소 조회 | 🔴 S-6·S-5 |
| **B. 워크스페이스** | 대상 저장소를 호스트에 가져온다 | 🔴 S-3·S-4 |
| **C. 코딩** | LLM 이 계획대로 파일을 고친다 | 🔴 S-4 |
| **D. 산출** | 포맷 · diff · 영속화 | 🔴 S-4 |

🔴 **A 만 머지되고 B 가 빠지면 후보가 `IMPLEMENTING` 에 갇힌다** — `#24` 가 `implement` 를 안 연 이유다.

⚠️ **「한 PR 이라 그 최악이 없어진다」는 틀렸다.** 한 PR 은 **머지 단위**를 묶을 뿐,
**A 는 됐는데 B 가 막히는 상황**을 막지 못한다. 그리고 §6 이 그 막힘 후보를 **셋이나** 올려 뒀다.

**그래서 중단 규칙을 명시한다.**

> 🔴 **§6 의 미지 중 하나라도 깨지면 A 를 단독으로 머지하지 않는다.** 전부 되돌리고
> 이슈에 실측 결과를 남긴다. 「게이트만 먼저 열어 두자」는 선택지가 **없다.**

⚠️ `CandidateApprovalApiTest.착수와_PR생성_엔드포인트는_없다_S6` 는 **엔드포인트를 열면 테스트를
고치게** 할 뿐 **실행기가 있는지는 보지 않는다.** 그 테스트를 「구조적 강제」로 세지 않는다.

---

## 1. 요구사항 — 체크박스 19개 매핑

| # | 요구 | 어디서 다루나 |
|---|---|---|
| 1 | 계획 목록대로 수정 · **계획 밖 파일을 건드리면 중단** | D-2 · **D-7** · 검증 2·3 |
| 2 | 규약이 테스트 요구하면 테스트 생성 | §4 C2 (`ContributionConstraints.testsRequired`) |
| 3 | 포맷터 적용 | §4 D1 |
| 4 | diff → `generated_change` 영속화 | §4 D3 · **D-8(스크럽)** |
| 5 | 모든 실행 샌드박스 경유 | §4 D1 · **B4(오케스트레이터)** |
| 6 | `AgentRun` stage·attempt·토큰 기록 | §4 C3 |
| 7 | `POST /implement` 개방 + 404 회귀 **수정** | §4 A3·A4 |
| 8 | `implement` 가 PR 까지 안 간다 | §4 A2 · 검증 6 |
| 9 | `PolicyClearance` 로 `startImplementing` | §4 A2 |
| 10 | 후보→이슈→`repositoryId` 조회 | D-5 · §4 A1 |
| 11 | **통행증과 후보가 같은 저장소** — 호출자가 보장 | **D-5 불변식 · 검증 4** |
| 12 | 통행증은 스냅샷(TOCTOU) · 샌드박스는 트랜잭션 밖 | §4 A2 |
| 13 | `PolicyClearance` 가 `adapter/in` 안 넘음 | 검증 5 |
| 14 | 재시도 바퀴마다 통행증 재확인 여부 **결정** | **D-6** |
| 15 | `SelectedFile` 재구성 안 함 | D-3 |
| 16 | 새 경로 만들면 스크럽 강제 + 돌연변이 | D-3 · 검증 9 |
| 17 | 「값 타입 예외」≠「스크럽돼서 안전」 | D-3 |
| 18 | 보류·금지 후보가 구현 단계 못 감 (403) | 검증 1 |
| 19 | 보류는 사람이 해소 (Q-8) | §7 — 기존 경로를 **깨지 않는다** |

---

## 2. 🔴 설계 결정

### D-1. 워크스페이스를 무엇이 채우나 — 능력 + JGit 어댑터

`CodeSandbox` 는 `Warm`·`SeedCache`·`Execute` 셋뿐이고 **셋 다 이미 채워진 워크스페이스를
전제**한다. `SandboxWorkspace.under()` 가 `Files.isDirectory` 를 요구하므로 **누군가 먼저
디렉토리를 만들어야** 샌드박스가 성립한다.

| 안 | 판정 |
|---|---|
| 호스트 `ProcessBuilder("git","clone")` | ❌ **S-3 의 실행체를 만들며 S-3 가드를 `safety-ok` 로 우회**하는 셈 (#17 이 docker CLI 를 기각한 근거와 같다) |
| `WarmCommand` 로 clone | ❌ `WarmCommand(workspace, buildTool, image, limits)` — **argv 자리가 없고** `argv()` 가 리터럴 고정이다(코드 확인). 게다가 워밍도 **채워진 워크스페이스**를 받는다 |
| **sealed 에 `CloneCommand` 추가** | 🟡 **구조적으로 가능하다** — 「네트워크 열림 + argv 는 우리 고정」은 `WarmCommand` 가 이미 하는 일이다. **이 PR 에서 채택하지 않는 이유는 따로 적는다** (아래) |
| **능력 `TargetWorkspaceSource` + JGit** | ✅ **채택** |

⚠️ **「샌드박스 clone 은 불가능하다」는 과장이었다.** sealed 를 넓히는 길이 있고, `WarmCommand`
자신이 그 패턴의 증거다. 정확한 표현은 **「이 PR 에서 sealed 를 건드리지 않기로 한다」**이고,
근거는 ⓐ 기본 이미지에 `git` 이 있다는 보장이 없다 ⓑ clone 실패 진단이 **종료코드로만** 온다
ⓒ `SandboxCommand` 는 #17 이 막 확정한 계약이라 두 이슈가 동시에 건드리면 충돌한다.

#### 🔴 JGit 채택 근거에서 「순수 Java」를 뺀다

초안은 「프로세스를 띄우지 않는다(순수 Java)」를 **근거 자리**에 두고, 동시에 §6 미지에
「JGit 이 `ProcessBuilder` 를 쓰지 않는가」를 올려 뒀다. **하중을 받는 문장이 같은 문서에서
미지로 분류돼 있었다** — 추측을 근거 자리에 둔 것이다.

**실제 근거는 이것이다.**

- **clone 명령을 우리가 argv 로 조립하지 않는다** — 문자열 주입면이 없다
- **실패가 예외로 온다** — 종료코드 해석이 필요 없다
- 능력 인터페이스가 domain 에 있어 **갈아끼우는 비용이 어댑터 한 장**이다 (Q-11)

🕳 **먼저 적는 한계 — 가드 둘이 이것을 보지 못한다.**
`safety-boundary-check.sh` 와 `HostExecutionAbsenceTest` 는 **`src/main/java` 의 문자열**만 본다.
**jar 안의 `ProcessBuilder` 는 둘 다 못 본다.** 즉 「가드가 초록이다」는 JGit 채택의 증거가
**전혀 아니다.** JGit 이 `FS.discoverGitSystemConfig()` 에서 시스템 `git` 을 spawn 할 수 있으므로
**B2 에서 `SystemReader` 를 억제하고 그 사실을 실측한다**(§6).

### D-2. 코드를 누가 고치나 — 능력 `CodingAgent` (2층)

```
agent/domain/LanguageModel (1층)
  └ candidate/domain/CodingAgent (2층)
      └ candidate/adapter/out/llm/LlmCodingAgent
```

LLM 이 계획 밖 경로를 돌려주면 **그 자리에서 중단**한다. ⚠️ **다만 이것은 조기 차단이지
게이트가 아니다** — D-7 이 게이트다.

### D-3. `SelectedFile` 을 재구성하지 않는다

#15 가 compact 생성자에 스크럽을 강제해 뒀다. `String` 을 받는 팩토리를 하나 만들면
**그 보증을 우회**하고 기존 테스트는 **그대로 초록**이다.

⚠️ **「값 타입이라 규율 ④ 예외」와 「스크럽된 값이라 건너가도 안전」은 다른 보증이다.**
전자가 유지되면서 후자만 깨질 수 있고, **그때 증상은 아무것도 빨개지지 않는 것**이다.

### D-4. diff — JGit 으로 워크스페이스에서

### D-5. 후보 → 저장소 — `issue`·`repository` 두 도메인에서 값으로 받는다

후보는 `issueId` 만 들고 있어 **자기 저장소를 모른다.** 규율 ④ 때문에 엔티티를 직접 못 읽는다.

```
issue/application     → IssueLocation(issueId, repositoryId, issueNumber)
repository/application → RepositoryCoordinates(owner, name) + ContributionConstraints
```

🔴 **`repositoryId` 만으로는 clone 도 캐시 볼륨도 못 만든다.** clone 에 `owner/name` 이 필요하고
`SandboxCacheVolume.forRepository(owner, name)` 도 좌표를 받는다. **좌표 조달 경로를 §4 에 행으로 둔다** —
없으면 구현 중 「그냥 import 하자」가 나온다.

🔴 **불변식** — `clearanceFor` 는 **후보의 저장소 id 로만** 부른다.
`startImplementing` 은 이것을 검증하지 못한다(후보가 자기 저장소를 모른다).
**「그 한 줄이 이 이슈의 몫」**이므로 검증 4 로 고정한다 — 없으면 다음 사람이
`clearanceFor(아무 id)` 로 고쳐도 **아무것도 빨개지지 않는다.**

### D-6. 🔴 재시도 바퀴마다 통행증을 다시 받나 — **안 받는다. 근거는 비용 하나뿐이다**

`retryImplementation` 은 통행증을 받지 않는다. **같은 바퀴이므로 설계상 맞다.**

**채택 근거 — 비용.** 매 바퀴 확인은 대외 호출을 3배로 늘린다.

⚠️ **초안이 든 안전 근거 두 개를 걷어낸다. 둘 다 서지 않는다.**

| 초안의 근거 | 왜 틀렸나 |
|---|---|
| 「#22·#23 이 막는다」 | **#23 의 수용조건에 정책 재확인이 없다.** #23 이 재확인하는 것은 「활성 PR 존재」(S-2 방어)이지 S-5 가 아니다. **존재하지 않는 방어를 가리켰다** |
| 「막아서 잃는 것이 되돌릴 수 있으면 막지 않는다」 | **`external-deps.md` 의 표를 거꾸로 읽었다.** 그 표는 **규약 판정(#7)을 fail-closed 쪽**에 두고 레이트리밋(#8)만 「막지 않는다」로 둔다. 정책 판정을 레이트리밋 사례에 갖다 붙였다 |

🕳 **그러므로 잔여 위험을 정직하게 적는다.**

> **재시도 루프가 도는 동안 대상 저장소가 AI 기여 금지로 바뀌는 것을 지금 막는 것은 없다.**
> 그 후보는 최대 3바퀴를 계속 돌고 `READY_FOR_PR` 까지 간다. 밖으로 나가지는 않지만
> (#22·#23 이 별도 게이트), **「나가는 것을 막는 장치」가 정책을 본다는 보장은 없다.**

🔴 `retryImplementation` javadoc 에 **이 잔여 위험을** 남긴다 — safety-reviewer 가 #24 에서
🟡 로 짚은 자리다. 「#22·#23 이 막는다」로 적지 않는다.

### D-7. 🔴 계획 밖 파일 판정은 **diff 의 경로 집합**에 건다

이슈는 「계획에 없는 파일을 **건드리면** 중단」이다. **LLM 출력 검증만으로는 부족하다** —
샌드박스에서 도는 **포맷터가 워크스페이스를 RW 로 잡고 임의 파일을 고친다.**
`spotlessApply` 한 번이면 전 저장소가 바뀐다.

> **diff 의 경로 집합 ⊆ 계획의 경로 집합.** 아니면 중단한다.

이것이 최종 게이트이고, D-2 는 그 앞의 조기 차단이다.

### D-8. 🔴 `GeneratedChange.diff` 의 스크럽을 **이 이슈가 세운다**

등록표가 그렇게 지목해 뒀다.

```java
Map.entry("GeneratedChange.diff", new Decision(Mechanism.PENDING,
        "#18 — 대상 저장소 코드 조각이 그대로 담긴다. 저장소가 시크릿을 커밋해 뒀으면 …"))
```

`PENDING` 은 **「쓰는 코드가 아직 없다. 해당 이슈가 강제 지점 또는 값 타입을 세워야 한다」**이고,
**이 이슈가 바로 그 「쓰는 코드」를 만든다.** 같은 파일이 선례를 박아 뒀다 —
*「낡은 `PENDING` 을 그대로 두면 이 표가 알리바이가 된다」*.

- `GeneratedChange` 의 **정적 팩토리를 유일한 생성 경로**로 두고 거기서 스크럽을 강제한다
- 🔴 **돌연변이로 확인한다** — 스크럽을 빼면 몇 건이 빨개지는지 숫자로 적는다
- `testResult`·`SandboxResult.output` 은 **#19 담당**(합의). `reviewResult` 는 #20

⚠️ 초안의 S-4 판정은 **송신 쪽(`SelectedFile`)만** 봤다. 이 이슈가 여는 유출구는 **영속 쪽**이다.

### D-9. 🔴 clone 은 **익명**으로 한다 — 토큰을 워크스페이스에 앉히지 않는다

JGit clone 이 만든 `.git/config` 에는 원격 URL 이 남는다.
`https://x-access-token:<PAT>@github.com/...` 로 clone 하면 **토큰이 워크스페이스 파일로 앉고**,
그 워크스페이스를 `Warm`·`Execute` 가 **RW 로 바인드**하며 거기서 **신뢰할 수 없는 빌드
스크립트가 돈다.**

🔴 `SandboxCommand` 의 방어는 **환경변수 축에만** 서 있다(「환경변수를 받는 자리가 없다」).
**파일 축은 비어 있었다.** 그리고 Q-4 의 잔여 위험 문장이 *「호스트는 안전하다 — 바인드 2개 ·
소켓 없음 · **시크릿 없음** · 자원 상한」* 이라고 단언하는데, **토큰이 들어가면 그 문장이 거짓이 된다.**

| 결정 | |
|---|---|
| **익명 clone 고정** | 대상은 공개 저장소다. 토큰이 필요 없다 |
| 레이트리밋 | clone 은 REST 레이트리밋과 별개다. **「리밋 걸리니 토큰 붙이자」가 나올 자리**라 여기 못 박는다 |
| 🔴 검증 | **워크스페이스에 시크릿 패턴이 없다**를 단언한다 (검증 8) |

### D-10. 서브모듈·훅·필터를 끈다

| 끄는 것 | 왜 |
|---|---|
| 서브모듈 | 임의 URL 을 따라간다 |
| 훅 | 이후 JGit 작업이 워크스페이스 훅을 보지 않게 |
| `.gitattributes` clean/smudge 필터 | 🔴 **체크아웃 중 실행되는 명령이다.** 「clone 은 읽기」의 예외 |

⚠️ **「clone 은 실행이 아니라 읽기」는 부분적으로만 참이다.** clone 자체는 맞지만 필터는 다르고,
체크아웃된 워크스페이스는 **RW 로 샌드박스에 들어간다.**

### D-11. 검증 단계와의 이음매 — `ChangeVerifier` (#19 합의)

```
candidate/domain/ChangeVerifier                         ← 이 이슈가 선언
  └ candidate/adapter/out/sandbox/SandboxChangeVerifier  ← #19 가 구현
```

```java
public record VerificationRequest(
        Long candidateId,
        String workspacePath,                 // 구현이 SandboxWorkspace 로 다시 만든다 — 검증 재실행
        ContributionConstraints constraints,  // repository 의 값 타입 (규율 ④ 예외) — 이미 있다
        Set<String> plannedPaths)             // D-7 판정에 필요
```

🔴 **fail-closed 기본값을 이 PR 이 함께 둔다.** #19 가 아직 안 꽂히면 후보가 갇히는 대신
`FAILED` 로 떨어지고 **사유에 「검증기가 배선되지 않았다」를 명시**한다 —
진짜 검증 실패와 구분돼야 #21 의 에러 분석이 헛돌지 않는다.

**루프는 #21 이다.** 이 PR 은 **1바퀴만** 돌리고 `attempt` 상한 판정을 건드리지 않는다.

---

## 3. 🔴 두 번째 시크릿 입구 — clone 한 워크스페이스

`SecretFilePolicy` 의 소비자는 지금 **`BuildRepositoryContextUseCase` 하나뿐**이다(코드 확인).
그것은 **GitHub API 경로**에 배선돼 있다.

🔴 **clone 은 두 번째 입구다.** 재시도 2·3바퀴째에 모델이 봐야 할 것은 **「지금 워크스페이스의
파일」**이지 #15 가 API 로 떠온 스냅샷이 아니다. 그리고 clone 은 `.env`·`*.pem`·`id_rsa` 를
커밋해 둔 저장소라면 **그것까지 통째로 가져온다.**

> **워크스페이스 파일을 프롬프트에 넣는 모든 경로는 `SecretFilePolicy` 를 통과한다.**

⚠️ **경로 배제와 스크럽은 순서가 다른 두 방어이고 서로를 대신하지 않는다**(glossary).
키 파일에는 우리가 모르는 형식의 자격증명이 있어 패턴 매칭만으로는 「가렸다」고 말할 수 없다.

---

## 4. 구현 단계

### A. 착수 게이트

| # | 파일 | 내용 |
|---|---|---|
| A1 | `issue/application/FindIssueLocationUseCase` + `repository` 좌표 조회 | `IssueLocation` · `RepositoryCoordinates` · `ContributionConstraints` |
| A2 | `candidate/application/ImplementCandidateUseCase` | 🔴 트랜잭션 3분할 · 🔴 **PR 까지 가지 않는다** |
| A3 | `CandidateController` | `POST /{id}/implement` |
| A4 | `CandidateApprovalApiTest` | 404 회귀를 **고친다 — 지우지 않는다** |

**트랜잭션 경계** — 통행증·`startImplementing`·저장 = 짧은 트랜잭션 / 워크스페이스·LLM·샌드박스
= **밖**(최대 30분) / 결과 영속화 = 다시 짧은 트랜잭션.

### B. 워크스페이스

| # | 파일 | 내용 |
|---|---|---|
| B1 | `agent/domain/TargetWorkspaceSource` | 능력 — clone · 브랜치 · diff |
| B2 | `agent/adapter/out/git/JGitWorkspaceSource` | 🔴 익명 · 서브모듈·훅·필터 차단 · **`SystemReader` 억제** |
| B3 | `FakeTargetWorkspaceSource` | 실패 모드 재현 |
| **B4** | `agent/application/SandboxPipeline` | 🔴 **warm → seed → execute 오케스트레이터.** `ExecuteCommand` 는 `cacheVolume` 없이 생성되지 않는다 |
| **B5** | `RepositoryPolicy` → `SandboxCommand` 변환 | 🔴 `javaVersion` 은 **`SandboxImages` 화이트리스트**를 반드시 거친다. **엔티티를 import 하지 않는다** |
| **B6** | 저장소당 워밍 1건 **프로세스 내 락** | Q-4 가 해법까지 적어 둔 **미구현** 항목 |
| B7 | `build.gradle.kts` · `libs.versions.toml` | JGit |

### C. 코딩

| # | 파일 | 내용 |
|---|---|---|
| C1 | `candidate/domain/CodingAgent` | 능력 (2층) |
| C2 | `candidate/adapter/out/llm/LlmCodingAgent` | 계획 밖 경로 중단 · `testsRequired` 면 테스트 생성 |
| C3 | `AgentRun` 기록 | `LlmCallSite.CODE` (이미 존재) · stage·attempt·토큰 |
| C4 | `FakeCodingAgent` | 깨진 출력·계획 이탈 재현 |

### D. 산출

| # | 파일 | 내용 |
|---|---|---|
| D1 | 포맷 — `ExecuteCommand` | 샌드박스 경유 |
| D2 | 🔴 **diff 경로 집합 ⊆ 계획 경로 집합** | D-7 게이트 |
| D3 | `GeneratedChange` 정적 팩토리 + **스크럽 강제** | D-8 · 등록표 `PENDING → FORCED_POINT` |
| D4 | `ChangeVerifier` 선언 + fail-closed 기본값 | D-11 |

---

## 5. 검증

| # | 검증 | 조항 |
|---|---|---|
| 1 | 🔴 보류·금지 저장소 후보가 구현 단계로 못 간다 — **403** | S-5 |
| 2 | LLM 이 계획 밖 경로를 돌려주면 중단 | — |
| 3 | 🔴 **diff 가 계획 밖 파일을 포함하면 중단** (포맷터가 건드린 경우) | — |
| 4 | 🔴 **다른 저장소의 통행증으로는 착수할 수 없다** | S-5 |
| 5 | `PolicyClearance` 가 `adapter/in` 에 없다 (ArchUnit) | S-6 |
| 6 | 🔴 `implement` 가 PR 을 만들지 않는다 | S-2·S-6 |
| 7 | 🔴 이 단계에 **push 경로가 존재하지 않는다** | S-1 |
| 8 | 🔴 **워크스페이스에 시크릿이 앉지 않는다** (`.git/config` 포함) | S-3·S-4 |
| 9 | 🔴 `GeneratedChange.diff` 스크럽 — **돌연변이 숫자** | S-4 |
| 10 | 트랜잭션 안에 샌드박스·LLM 호출이 없다 | — |
| 11 | 모든 대상 저장소 실행이 샌드박스 경유 | S-3 |

🔴 **검증 7 의 증거 등급을 정한다.** 이 PR 이 **JGit 을 들여오므로 `git.push()` 가 API 한 줄
거리**가 된다. owner 어설션은 #22 에 있고 여기엔 없다. **문자열 검사가 아니라 ArchUnit** 으로
「`org.eclipse.jgit` 의 push 계열을 부르는 타입이 없다」를 고정한다 — 문자열 가드는
jar 안을 못 보는 것과 같은 이유로 약하다.

---

## 6. 🕳 아직 모르는 것 — 실측으로 닫는다

| 미지 | 깨지면 |
|---|---|
| 얕은 clone(depth=1)에서 브랜치·diff 가 되는가 | D-1 의 depth 결정이 무너진다 |
| **JGit 이 시스템 `git` 을 spawn 하는가** (`SystemReader` 억제 전후) | D-1 의 근거·S-3 한계 서술이 바뀐다 |
| `spring-kafka` 워밍→씨딩→오프라인 실행 통과 | **Q-4 종결의 전제** |
| **toolchain 자동 프로비저닝** — 빌드가 요구하는 JDK 가 이미지와 다르면 네트워크를 탄다 | 오프라인 실행이 깨진다 |
| **dynamic version · SNAPSHOT** — RO 캐시로 해결되지 않는다 | 〃 |

🔴 **셋 다 「될 것이다」로 적지 않는다.** 어긋나면 이 문서를 고치고, **§0 의 중단 규칙**을 적용한다.

---

## 7. 게이트 판정

### 안전 경계

| 조항 | 접촉 | 어떻게 |
|---|---|---|
| **S-4** | 🔴 **직접 — 입구가 셋** | ⓐ 프롬프트(`SelectedFile` 재구성 금지) ⓑ **clone 워크스페이스**(`SecretFilePolicy` 배선 — §3) ⓒ **영속**(`GeneratedChange.diff` 스크럽 — D-8) |
| **S-3** | 🔴 **직접** | 대상 명령은 전부 샌드박스 · clone 은 익명·필터 차단 · 🕳 **가드 둘이 jar 안을 못 본다**(D-1) |
| **S-6** | 🔴 **직접** | 두 번째 게이트 개방 · PR 까지 안 감 · 404 회귀를 고치고 지우지 않음 |
| **S-5** | 🔴 **직접** | 통행증은 `clearanceFor` 로만 · **후보의 저장소로만 조회**(D-5) · 🕳 **재시도 중 정책 변경은 막지 못한다**(D-6) |
| **S-1** | 🔴 **부재로** | push 경로 없음 — **ArchUnit 으로 고정**(검증 7). JGit 도입이 이 항목을 가장 값싸게 무력화한다 |
| S-2 | 접촉 | PR 생성 경로를 만들지 않는다 |

### 미결 대조

| 항목 | 처리 |
|---|---|
| **Q-4** | 🔴 이 이슈가 **종결 조건의 전제**를 만든다. **종결 조건 자체는 「`spring-kafka` 실측 1회 통과」**이고, Q-4 는 *「Maven 대상에서 다시 열어야 하니 ✅ 로 닫지 말라」*고 명시했다 — **닫지 않는다.** Maven 은 **지원하지 않는다고 실패시킨다**(조용히 네트워크를 여는 것이 최악) |
| **Q-11** | JGit 채택 — 근거를 D-1 에 적었다. Q-1 을 일반화한 것이 아니다 |
| Q-5 · Q-8 | 따른다(바꾸지 않는다) |
| Q-6 | `verify` 엔드포인트를 **열지 않는 근거**로 인용 — `attempt` 가 사이클 단위라 세 단계를 사람이 각각 호출하면 카운터가 성립하지 않는다 |
| Q-3 | 🟡 30분 실행이 API 스레드를 점유한다 — **이 PR 이 압력을 키운다.** 판단 재료로 PR 에 남긴다 |

---

## 8. 초안이 틀렸던 곳

| # | 무엇 | 왜 틀렸나 |
|---|---|---|
| 1 | D-6 의 안전 근거 둘 | **존재하지 않는 방어(#23)를 가리켰고**, `external-deps` 의 fail-closed 표를 **거꾸로 읽었다** |
| 2 | FR-1 을 LLM 출력 검증으로 좁힘 | 이슈는 「**건드리면**」이다. **포맷터가 임의 파일을 고친다** |
| 3 | S-4 를 송신 쪽만 봄 | **영속(`GeneratedChange.diff`)과 clone 워크스페이스**가 빠졌다. 등록표가 이 이슈를 담당으로 지목해 뒀다 |
| 4 | clone 자격증명 미언급 | 토큰이 `.git/config` 로 앉아 **샌드박스가 읽는다.** Q-4 의 「시크릿 없음」이 거짓이 된다 |
| 5 | 「순수 Java」를 근거 자리에 | 같은 문서 §6 이 그것을 **미지**로 올려 뒀다 |
| 6 | 「Q-4 종결 조건」 | Q-4 는 #18 을 **전제**로 부르고, **닫지 말라**고 적어 뒀다 |
| 7 | 「한 PR 이 최악을 없앤다」 | 머지 단위를 묶을 뿐이다. **중단 규칙**이 실제 방어다 |
| 8 | 체크박스 11·17 누락 | 통행증-후보 저장소 불변식 · 「두 보증은 다르다」 |

---

## 9. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-27 | smileboy0014 | 초안 |
| 2026-09-27 | smileboy0014 | 검토 반영 — **blocker 5건**(스크럽 등록표 · clone 토큰 · 계획 밖 파일 판정 · D-6 논증 · `SecretFilePolicy` 두 번째 입구) + Q-4 과장 정정 · #19 와 `ChangeVerifier` 계약 합의 |
