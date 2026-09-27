# PLAN-19 — 검증 파이프라인 (compile → test → diff 검사)

> 타입 `feature` · PRD §15. **이 게이트가 제품의 본질이다**(PRD §30).
>
> **rev.4 (구현 후)** — 구현하며 계획과 달라진 것 넷을 §3.1 에 적었다. 설계 축은 그대로다.
>
> **rev.3** — `gap-analyzer` 검토가 🔴 6건을 냈고 그중 하나는 **파이프라인을 전멸시키는
> 설계**였다. 아래 §1 이 그것이다. rev.1→2 는 소유 도메인 정정(§0.1).

---

## 0. 스코프 판정

| 항목 | 답 |
|---|---|
| 소유 도메인 | **`candidate`** (rev.2 정정 — §0.1) |
| 레이어 | 능력 = `candidate/domain` · 구현 = `candidate/adapter/out/sandbox` |
| 능력 인터페이스 | **`ChangeVerifier`** — #18 이 선언, 이 PR 이 구현 |
| 🔴 대외 호출 위치 | 샌드박스는 최대 30분 → **트랜잭션 밖**. 영속화만 짧은 트랜잭션 |
| 상태 전이 | **만들지 않는다.** `TESTING` 전이·`attempt` 는 #18·#21 |
| `Clock` | 주입 |

### 0.1 소유 도메인을 `agent` → `candidate` 로 정정 (rev.2)

`agent/domain` 의 `LanguageModel`·`CodeSandbox` 는 **1층 능력**이고, 그 위 2층은
**소비 도메인**이 갖는다 — `IssueAnalyst`(#11)·`ImplementationPlanner`(#16) 둘 다
`candidate/domain` 선언 + `candidate/adapter/out/{기술}` 구현이다.

`agent` 에 두면 `GeneratedChange`·`ContributionCandidate` 를 import 하게 되고,
`codemaps/architecture.md` 의 단방향(`candidate → agent`)을 역행한다.

---

## 1. 🔴 rev.3 최우선 정정 — 「판정 불가는 실패」가 파이프라인을 전멸시킬 뻔했다

rev.1~2 는 이렇게 적었다.

> `truncated` 이고 성공 문자열이 없으면 **판정 불가**이고, 판정 불가는 **실패**다.

**전제가 이 아키텍처에서 성립하지 않는다.**

| 내가 가정한 것 | 실제 |
|---|---|
| 「파이프 종료 코드 함정」이 여기 있다 | 🔴 **없다.** `DockerCodeSandbox` 는 컨테이너 `wait` 의 종료코드를 **직접** 받는다. 파이프가 없으므로 **exit code 는 이미 신뢰할 수 있다** |
| 출력 절단은 드물다 | 🔴 **일상이다.** `sandbox.max-output-chars` = **200,000**(실측). 대형 저장소의 전체 테스트 출력은 이를 넘는다 |

둘을 합치면 — **모든 검증이 `UNDETERMINED` → 모든 후보가 재시도 소진 → `FAILED`.**

🔴 **방어가 스스로를 잠그는 구조다.** `external-deps.md` 가 기록해 둔 그 사고와
**같은 모양**이다(「리밋도 안전 경계니 막자」로 짠 선제 차단이 갱신할 응답이 오지 않아
재기동 전까지 모든 호출을 실패시킨 건).

### 고친 축 — `UNDETERMINED` 는 **판정이 출력 파싱에 의존할 때만**

| 판정 근거 | `truncated` 의 영향 |
|---|---|
| **종료코드** (컴파일 통과 · 테스트 통과) | **없다.** 로그가 잘려도 exit code 는 온전하다 → `PASSED`/`FAILED` |
| **출력 파싱** (diff 내용 검사) | 🔴 `UNDETERMINED` |
| `timedOut` | exit code 무의미 → `FAILED`. `succeeded()` 가 이미 처리 |

⚠️ **「성공 문자열을 함께 본다」를 exit code == 0 에 얹는 추가 게이트로 두지 않는다.**
이슈 문구를 우리 구조에 기계적으로 옮긴 것이고, 대가가 파이프라인 전멸이다.

⚠️ 「판정 불가는 실패」라는 **원칙 자체는 유지**한다 — 적용 범위만 좁힌다.
`external-deps.md` 의 실패 방향 기준(되돌릴 수 없는 쪽을 피한다)은 그대로다.

---

## 2. 단계 — rev.3 에서 **다섯에서 셋으로 줄인다**

rev.1~2 는 `COMPILE · UNIT_TEST · INTEGRATION_TEST · FORMAT · DIFF` 다섯을 뒀다.
**둘은 입력이 없다.**

| 단계 | rev.3 | 왜 |
|---|---|---|
| `COMPILE` | ✅ 유지 | `buildCommand` |
| `TEST` | ✅ **UNIT/INTEGRATION 을 하나로 합친다** | 아래 §2.1 |
| ~~`FORMAT`~~ | 🔴 **뺀다** | 아래 §2.2 |
| `DIFF` | ✅ 유지 | §2.3 |

### 2.1 `UNIT_TEST`/`INTEGRATION_TEST` 분리는 근거가 없다

- `ContributionConstraints` 에 **`testCommand` 는 하나**다. 두 번째 명령의 출처가 없다
- `codemaps/domain.md` 도 「Unit / Integration Test | `test_command` 로 실행」으로 **한 줄**이다
- 🔴 **더 큰 문제** — 실행 단계는 **`network=none`**(S-3 · Q-4 ③)이다. 대상 저장소의
  통합 테스트는 Testcontainers·브로커를 요구하는 것이 보통이고(Phase 1 `spring-kafka` 가
  그렇다), **정상 코드가 통합 테스트에서 실패**한다. Docker 소켓 마운트는 S-3 금지라 우회로도 없다

**하나의 `TEST` 단계**로 두고, **「`network=none` 에서 통합 테스트가 성립하지 않을 수 있다」를
알려진 한계로 PR 본문에 적는다.** Q-4 가 열려 있는 이유와 같은 축이다 — 닫지 않는다.

### 2.2 `FORMAT` 을 빼는 이유 — 셋 다 나쁘고 하나만 정직하다

포맷/린트 **명령의 출처가 없다**(실측: `RepositoryPolicy` 에 `javaVersion`·`buildCommand`·
`testCommand` 뿐).

| 선택지 | 판정 |
|---|---|
| `spotlessCheck`·`checkstyleMain` 을 **하드코딩** | 🔴 **S-5 위반.** 대상 저장소의 포맷 규약을 우리 어휘로 대체하는 것이고, 그 태스크가 없으면 **코드 문제가 없는데 `FAILED`** |
| `ContributionConstraints` 에 `formatCommand` **추가** | #7·#16 계약 + 마이그레이션 변경. **이 PR 범위 밖** |
| ✅ **이번 범위에서 빼고 사유를 남긴다** | 「검사했다」와 구분되게 기록된다 |

🔴 **그리고 넣었어도 위험했다.** #18 검토가 짚은 것 — **포맷터는 워크스페이스를 RW 로 잡고
파일을 고친다.** `spotlessApply` 를 고르면 **검증 단계가 스스로 「계획 범위 밖 변경」을 만들고**
바로 다음 `DIFF` 가 그것을 위반으로 잡는다. 검증이 검증 대상을 오염시킨다.

→ **enum 에서 뺀다.** 넣어 두면 「곧 할 것」으로 읽혀 다음 사람이 (a) 를 고른다.
`#7` 이 포맷 명령을 뽑게 되면 그때 단계를 만든다.

### 2.3 `DIFF` — rev.3 에서 판정 규칙을 실제로 정한다

rev.1~2 는 「diff 검사 규칙(계획 범위 밖 · 디버그 잔재 · 대용량 바이너리)」 **한 줄**이었다.
이름만 있고 규칙이 없었다.

| 항목 | 규칙 |
|---|---|
| **계획 범위 밖** | diff 의 경로 집합 ⊆ `plannedPaths`. **정확 일치**로 본다(prefix 는 `src/` 하나로 전부 통과된다). ⚠️ **생성된 테스트 파일**은 계획에 있어야 한다 — #18 이 계획에 넣는다 |
| **디버그 잔재** | 🔴 **추가된 줄(`+`)만** 본다. 기존 코드에 `System.out.println` 은 흔하고, 문맥 줄까지 세면 **모든 diff 가 걸린다**. 패턴: `System.out.print*` · `printStackTrace` · `.printStackTrace()` |
| **대용량 바이너리** | 한 파일 추가분이 `verification.max-added-file-bytes`(기본 **256KB**)를 넘고 **텍스트가 아니면** 위반. 설정 키를 둔다 — 숫자를 코드에 박지 않는다 |

⚠️ **#18 도 산출 직전에 「계획 범위 밖」을 판정한다.** 중복이 아니라 **순서가 다른 방어**다
(`SecretFilePolicy`/`TokenRedactor` 와 같은 관계). **이쪽이 더 늦으므로 최종 게이트**다.

⚠️ diff 를 **출력 파싱으로** 얻으므로 §1 의 `UNDETERMINED` 가 **여기에만** 적용된다.

### 2.4 첫 실패에서 멈추고 나머지는 `SKIPPED`

컴파일이 깨졌는데 테스트를 30분 돌릴 이유가 없다. 이후 단계는 **`SKIPPED` 로 명시**한다 —
**기록이 없는 것과 건너뛴 것을 구분**한다(`/work` 빌드 판정이 「실행 안 됨」과 「통과」를
가른 것과 같은 축). `codemaps/domain.md` 의 「앞 단계가 실패하면 뒤를 실행하지 않는다」와 일치.

### 2.5 🔴 타임아웃 — 단계 합이 전체 상한을 넘는다

`sandbox.timeout-seconds` 는 **실행 1회 상한 1800s** 다. 단계가 셋이면 최악 **90분**인데
`agent.execution.timeout-seconds` 도 **1800** 이다. 그대로 두면 「30분짜리 검증」이 90분이 되고,
Q-3(전용 풀 1건)에서 **파이프라인 전체를 막는다.**

**전체 예산을 하나 두고 단계가 나눠 쓴다.** `verification.total-timeout-seconds`(기본 1800)에서
남은 시간을 각 단계 `SandboxLimits` 에 넘기고, 소진되면 남은 단계는
`SKIPPED`(사유: 예산 소진)이며 **전체 판정은 실패**다.

---

## 3. 구조

```
candidate/domain/
  ChangeVerifier        능력 — 🔴 #18 이 선언 (이 PR 은 구현만)
  VerificationRequest   값 — candidateId · coordinates · attempt · workspacePath
                              · ContributionConstraints · plannedPaths        (rev.4)
  VerificationSetupException  🔴 「검증을 시작조차 못 했다」 — 재시도 대상이 아니다  (rev.4)
  VerificationReport    값 — List<StageResult> + 전체 판정
  StageResult           값 — 🔴 compact 생성자가 요약을 redact
  VerificationStage     enum — COMPILE · TEST · DIFF
  StageOutcome          enum — PASSED · FAILED · UNDETERMINED · SKIPPED
  DiffInspection        §2.3 규칙
  CommandLine           🔴 String → argv 변환 (§4)

candidate/adapter/out/sandbox/
  SandboxChangeVerifier 구현 — CodeSandbox 를 단계별로 부른다
```

⚠️ `VerificationRequest` 에 **`attempt` 를 넣는다** — `AgentRun` 불변식이
「같은 사이클의 행이 같은 `attempt`」라, 호출자가 넘겨야 한다.

### 3.1 rev.4 — 구현하며 달라진 넷

계획이 틀렸다기보다 **계획이 정하지 않은 자리**였다. 넷 다 구현하다 답이 강제됐다.

| # | 무엇 | 왜 |
|---|---|---|
| 1 | `VerificationRequest` 에 **`coordinates` 추가** | 의존성 캐시 볼륨이 **저장소별**이다(`SandboxCacheVolume.forRepository`). 좌표 없이 만들면 전역 공유 볼륨이 되고, 한 저장소의 워밍이 남긴 것이 **다른 저장소의 실행을 오염**시킨다 (S-3 · Q-4) |
| 2 | **`VerificationSetupException` 신설** | 「테스트가 깨졌다」와 「명령이 없다·쉘 메타문자·Maven」을 같은 것으로 다루면 **재시도 루프가 고칠 수 없는 것을 3바퀴 돈다**(Q-6 예산 3×3). 전자는 코딩이 고치고 후자는 **사람**이 고친다. ⚠️ 「빌드 실패는 예외가 아니다」와 어긋나지 않는다 — 그 규율은 **게이트가 작동한 모습**을 예외로 내보내지 말라는 것이고, 이것은 **게이트를 돌리지도 못한 것**이다 |
| 3 | **`DIFF` 가 샌드박스를 2회 부른다** | `--numstat`(파일당 한 줄)로 **범위·바이너리·크기**를 먼저 보고, 통과한 뒤에만 본문(`--unified=0`)을 받는다. 한 덩어리로 받았으면 **큰 diff 하나가 범위 검사까지 `UNDETERMINED`** 로 만들고, 그러면 **가장 자주 쓰이는 게이트가 가장 자주 무력**해진다. 곁가지 이득 — 범위 검사를 통과한 시점엔 변경이 계획 파일 안이라 **본문이 잘릴 여지도 함께 줄어든다** |
| 4 | **`testCommand` 부재 → `TEST` 가 `UNDETERMINED`** | rev.3 은 `UNDETERMINED` 를 「출력 파싱에 의존하는 판정」으로 좁혔는데, 이것은 파싱과 무관하면서 **판정 근거가 없는** 경우다. 축을 「파싱 의존」이 아니라 **「판정할 근거가 있는가」**로 한 겹 넓혔다. 🔴 `SKIPPED` 로 두면 안 된다 — 그쪽은 「앞이 멈춰서」라 **앞의 실패가 이미 설명**하지만, 이것은 아무도 설명하지 않는 공백이다.<br>⚠️ **대가**: `spring-kafka` 의 `testCommand` 를 #7 이 못 뽑으면 후보가 통과하지 못한다. 그래도 **돌리지 않은 테스트를 「통과」로 적는 것**보다 낫다 — 그 순간 이 제품의 게이트가 사라진다 |

---

## 4. 🔴 `testCommand` 는 문자열이고 S-3 은 argv 를 요구한다

S-3: **「명령은 argv 다. 쉘을 경유하지 않는다.」** `sh -c "<문자열>"` 로 돌리면
`;`·`&&`·`$(…)` 가 살고 `FOO=bar cmd` 로 환경변수 주입까지 된다.

그런데 `buildCommand`·`testCommand` 는 **LLM 이 대상 저장소 문서에서 뽑은 문자열**(#7)이다.

**`CommandLine` 값 타입이 분해를 소유한다.**

- 공백 분리. **인용·이스케이프를 해석하지 않는다** — 해석하면 쉘 의미론을 재구현하게 된다
- 🔴 **쉘 메타문자(`; & | > < $ \` ( ) 개행)가 하나라도 있으면 거부**한다 →
  그 단계는 `FAILED`(사유: 명령을 안전하게 분해할 수 없음). **추측해서 돌리지 않는다**
- 거부는 **S-5 의 fail-closed 방향**이다 — 모르면 되돌릴 수 없는 쪽을 피한다

---

## 5. 영속화 — 🔴 #18 에 **하드 의존**이다 (완화책이 아니다)

이슈 완료 조건: 「실패 출력을 `generated_change.test_result` 에 저장(**시크릿 스크럽 후**)」.

그런데 `GeneratedChange` 에는 **팩토리도 쓰기 메서드도 없다.** 행이 없으면
`recordVerification` 을 부를 대상이 없고, §7 의 「**DB 재조회**로 스크럽 확인」도 작성 불가다.

🔴 **「#18 이 먼저 머지되면 맞춘다」는 완화책이 아니라 선후 의존**이다. 정직하게 적는다.

| #18 머지 전 | #18 머지 후 |
|---|---|
| `ChangeVerifier` 구현 + `VerificationReport` **반환**까지 완성 | `recordVerification(report, clock)` 배선 + DB 재조회 테스트 |
| ⚠️ 「저장」 완료 조건 **미충족** — PR 본문에 명시한다 | 충족 |

**생성 팩토리는 #18 소유**다(합의 완료). 이 PR 은 기록 메서드만 더한다.

### 5.1 등록표

| 행 | 현재 | rev.3 |
|---|---|---|
| `GeneratedChange.testResult` | `PENDING "#18"` | **`VALUE_TYPE`** — `StageResult` compact 생성자가 redact 하고, `testResult` 는 `StageResult` 들로만 조립된다 |
| `SandboxResult.output` | `PENDING "#18·#19 — 소비자가 아직 없다"` | ⚠️ **`PENDING` 유지, 사유만 갱신.** DB 로 가는 경로는 닫혔지만 **`output` 자체는 여전히 원문**이고 아무나 `result.output()` 을 부를 수 있다. 「단일 읽기 경로」를 강제하는 것이 없으므로 `FORCED_POINT` 는 **거짓 안전감**이다 — #20 이 남은 소비자다 |
| `GeneratedChange.reviewResult` | `PENDING "#19 — LLM 리뷰 원문"` | 🔴 **담당을 #20 으로 정정.** 리뷰는 #20 이다. 안 고치면 #19 머지 후 **닫힌 이슈를 가리키는 PENDING** 이 되고, 가드는 `#` 존재만 보므로 **조용히 통과**한다 |

---

## 6. 안전 경계 — rev.3 에서 **둘을 올린다**

| 조항 | rev.2 | rev.3 |
|---|---|---|
| 🔴 **S-5** | 「간접」 | **직접 접촉.** 이 PR 이 **대상 저장소의 빌드·테스트 명령을 실행하는 첫 코드**다. 우리 Gradle 어휘를 하드코딩하면 「우리 규약을 남의 저장소에 적용」이 된다 — §2.2 가 `FORMAT` 을 뺀 이유이자 §4 가 거부로 가는 이유 |
| 🔴 **S-2** | 「미접촉」 | **접촉.** 이 게이트의 **유일한 존재 이유**가 검증 안 된 코드의 Draft PR 진입을 막는 것이다. rev.2 는 §1 에서 그렇게 논증하고 표에는 「미접촉」으로 적었다 — **자기 논증과 모순**이었다 |
| **S-3** | 접촉 | 유지. `CodeSandbox` 경유 · `cleanedUp == false` 보고 · §4 argv · §2.1 `network=none` 한계 |
| **S-4** | 접촉 | 유지. `StageResult` compact 생성자가 스크럽 강제 (§5.1) |
| **S-6** | 접촉 | 유지. `verify` 미개방(§8) · 전이·카운터 미변경 |
| S-1 | 미접촉 | 유지 — push 경로 없음 |

## 7. 미결 대조

| 항목 | 처리 |
|---|---|
| **Q-4** (Gradle 한정) | 접촉. `BuildTool.requireSupported()` 에 맡긴다. ⚠️ 종결 조건(`spring-kafka` 실측)은 #18 이 워크스페이스를 채워야 가능 — **닫지 않는다.** §2.1 의 `network=none` 한계도 여기 붙는다 |
| **Q-6** (재시도 단위) | 접촉. `attempt` 증가·`FAILED` 전이를 안 건드린다. `VerificationRequest.attempt` 로 **받기만** 한다 |
| **Q-3** | 간접 — §2.5 전체 예산이 전용 풀 1건 전제에서 나온다 |
| Q-9 | `FakeCodeSandbox`. ⚠️ **단일 결과만 돌려주므로 확장이 필요**하다(§7.1) |

### 7.1 `FakeCodeSandbox` 확장이 필요하다 — #18 과 공유 파일

지금 `nextResult` **하나**를 모든 호출에 돌려준다. 파이프라인은 샌드박스를 **여러 번** 부르므로
「첫 실패에서 멈추고 나머지 `SKIPPED`」를 **검증할 수 없다.** 호출별 결과 큐를 더한다.

⚠️ `agent/domain` 의 `src/test` 라 **#18 과 공유**한다 — 순증(기존 메서드 유지)으로만 더한다.

## 8. `verify` 엔드포인트 — 🔴 **열지 않는다**

| 근거 | 강도 |
|---|---|
| 🔴 **Q-6 과 정면 충돌** | `attempt` = `CODE→VERIFY→REVIEW` **한 바퀴**로 확정됐다. 세 단계를 사람이 각각 호출하면 **「한 바퀴」라는 단위가 소멸**하고 `attempt` 를 누가 언제 올리는지 정의 불가가 된다. **확정된 결정과의 충돌**이라 가장 강하다 |
| 열면 건너뛸 수 있게 된다 | 중 — PRD §30(게이트를 느슨하게 = 제품 훼손) |
| S-6 목록에 없다 | 약 — **부재 논증**이다. 「빠졌을 수도」로 반박된다 |

⚠️ **반대 논거도 적어 둔다** — 「검증만 다시 돌리고 싶은 운영 상황(샌드박스 일시 장애)」.
답: 그것은 **전송/일시 장애 축**이고 `SandboxTransientException` 재시도로 흡수한다.
승인 게이트로 풀 문제가 아니다.

🔴 **`codemaps/domain.md` 승인 게이트 표에 「행으로」 넣는다.** 산문 각주로 두면
다음 사람이 표만 보고 연다 — 「빠뜨렸다」와 「안 열기로 했다」를 구분하라는 이슈 요구가 그것이다.

⚠️ PRD §23 은 peer 세션(#30)이 **「미정 · 판단은 #19 몫」**으로 두었다. 이 PR 이 확정되면
**이 PR 이 PRD 를 고친다** — 어느 시점에도 PRD 가 참이도록.

## 9. 검증

| 무엇 | 어떻게 |
|---|---|
| 단계별 판정 | `FakeCodeSandbox` 큐로 첫 실패 후 `SKIPPED` 단언 |
| 🔴 **truncated 회귀** | **exit code 0 + truncated → `PASSED`**(`UNDETERMINED` 아님). rev.3 이 고친 그 결함이 되돌아오면 빨개진다 |
| 🔴 **UNDETERMINED 는 DIFF 에만** | diff 출력이 잘리면 `UNDETERMINED` + 전체 실패 |
| 타임아웃 | `timedOut` → 실패. **전체 예산 소진** → 남은 단계 `SKIPPED` + 전체 실패 |
| 🔴 **스크럽** | 빌드 출력에 토큰 → `GeneratedChange.testResult` **DB 재조회**로 부재 확인 (#18 머지 후) |
| 🔴 **argv 거부** | `testCommand` 에 `;`·`&&`·`$(` → 거부되는지 |
| **디버그 잔재 오탐** | 기존 코드의 `System.out.println` 이 **문맥 줄**로 들어와도 안 걸리는지 |
| `cleanedUp=false` | 판정과 별개로 보고에 남는지 |
| 🔴 **돌연변이** | `StageResult` 의 `redact` 제거 → **몇 건 빨개지는지 숫자로** PR 에 |
| 전체 | `./gradlew build --rerun-tasks` — ⚠️ `> Task :test` 접미사와 요약 줄 확인 |

## 10. 범위 밖

- **#18 배선·`GeneratedChange` 팩토리** — §5. 🔴 **하드 의존**이지 완화책이 아니다
- **#21 재시도·`FAILED` 전이** · **#20 AI 리뷰**
- **`FORMAT` 단계** — §2.2. `#7` 이 포맷 명령을 뽑으면 그때
- **`network=none` 에서 통합 테스트** — §2.1. Q-4 와 함께 열어 둔다
- **`AgentRun` 에 VERIFY 기록** — 🔴 **경로가 없다.** `LlmCallSite` 는 `ANALYZE·PLAN·CODE·REVIEW·POLICY`
  뿐이고 `AgentRunRecorder` 는 `LlmUsage` 를 받는다. 검증은 LLM 호출이 아니라 토큰이 없다.
  **가짜 `LlmUsage` 를 넘기지 않는다** — 리포트를 호출자(#18·#21)에게 돌려주고 기록은 그쪽 몫이다
