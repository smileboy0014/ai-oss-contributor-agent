# PLAN-21: 재시도 전략 — 에러 분석 후 재시도, 상한 소진은 FAILED

**이슈**: [#21](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/21)
**type**: feature
**작성일**: 2026-09-27
**작성자**: smileboy0014
**베이스**: `70167f9` (#18 머지 직후)

## 1. 요구사항

### 배경

#18 이 `ImplementCandidateUseCase` 를 냈지만 **1바퀴만 돈다.** 코드에 그 사실이 세 군데 적혀 있다.

```java
// ⚠️ 지금은 1바퀴만 돈다 — CODE → VERIFY → REVIEW 루프와 attempt 상한 소진 판정은 #21 이다.
// 🔴 여기서 READY_FOR_PR 로 보내지 않는다 — 그 전이는 #21 의 루프가 판정한다.
// 후보 단위 실패 기록은 재시도 루프와 함께 서야 한다 — #21 이 그 자리다.
```

그래서 지금 상태는 이렇다.

| 지금 | 결과 |
|---|---|
| 검증 실패 → 즉시 `FAILED` | PRD §17 의 「3번 고쳐본다」가 **한 번도 일어나지 않는다** |
| 검증 통과 → **아무 전이도 하지 않는다** | 후보가 `IMPLEMENTING` 에 박혀 `READY_FOR_PR` 에 도달할 길이 없다 |
| `REVIEW` 단계가 **호출되지 않는다** | #20 의 `DiffReviewer` 가 배선되지 않은 채 서 있다 |
| 실패 사유가 **로그에만** 남는다 | `FAILED` 가 종단인데 「왜」가 API·DB 에 없다 |

이 이슈는 그 넷을 닫는다. Q-6 은 **이미 확정**이라(#36·#39) 여기서 정할 것은 아니고,
확정된 정의를 **실행 코드로 옮기는 것**이 이 이슈의 일이다.

### 기능 요구사항 (FR)

| # | 요구사항 | 근거 |
|---|---------|------|
| FR-1 | `CODE → VERIFY → REVIEW` **한 바퀴 = `attempt` 1**, 최대 `MAX_ALLOWED_ATTEMPTS`(3) 바퀴를 돈다 | 이슈 완료 조건 · Q-6 |
| FR-2 | `REVIEW` 단계를 배선한다 — `DiffReviewer` 호출 + **임계 판정은 호출자가** | 이슈 코멘트 2 · #20 handoff |
| FR-3 | 에러 분석 — 컴파일 오류 / 테스트 실패 / 리뷰 지적을 **구분해 다른 프롬프트로** 되먹인다 | 이슈 완료 조건 |
| FR-4 | 재시도 여부를 **타입이 답한다.** `switch` 를 쓰지 않는다 | 이슈 코멘트 3 |
| FR-5 | 같은 실패가 2회 반복되면 **조기 중단** | 이슈 완료 조건 |
| FR-6 | 상한 소진 → `FAILED` + **후보 단위 실패 사유를 `AgentRun` 에 남긴다** | 이슈 완료 조건 · #18 이관 |
| FR-7 | 전부 통과 → `REVIEWING → READY_FOR_PR` 전이 | #18 이 남긴 자리 |
| FR-8 | `agent.execution.max-retries` → **`max-attempts` 개명** (문서 3곳 동시) | Q-6 이 #21 로 지정 |
| FR-9 | 재시도마다 누적 토큰·비용이 기록된다 — `attempt` 를 정확히 넘긴다 | 이슈 완료 조건 |

### 비기능 요구사항 (NFR)

| # | 항목 | 기준 |
|---|------|------|
| NFR-1 | **비용 — 곱셈 예산** | 후보 1건당 LLM 호출 최대 **3 × (1 + `agent.llm.max-retries` 2) = 9회** (코딩) + 리뷰 동일. 샌드박스는 **3 × 최대 30분 = 90분** |
| NFR-2 | 타임아웃 | 바퀴마다 `agent.execution.timeout-seconds`(1800). 🔴 **루프 전체 상한은 두지 않는다** — 아래 R-3 |
| NFR-3 | 트랜잭션 | 루프 전체가 트랜잭션 **밖**. 커넥션이 90분 잡히는 것을 막는다 |
| NFR-4 | 관측 | 바퀴마다 `AgentRun` 행 · MDC `attempt` 갱신 · 상태 전이 로그 |

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키는가 |
|---|---|---|
| S-1 원본 저장소 쓰기 금지 | — | **push 경로를 만들지 않는다.** 루프는 `READY_FOR_PR` 에서 끝난다. `ForkPublisher` 를 import 하지 않는다 |
| S-2 항상 draft · 자동 머지 금지 | ✅ | 🔴 **`markPrCreated` 를 부르지 않는다.** 루프의 성공 종착은 `READY_FOR_PR` 이고 그 다음은 **세 번째 승인 게이트**(#23)다. 가드의 **형태**는 아래 D-7 — 허용목록이 아니라 **여집합 + 미끼**다 |
| S-3 샌드박스 밖 실행 금지 | ✅ | 검증은 `ChangeVerifier`(#19) 경유. 루프가 명령을 직접 조립하지 않는다 |
| S-4 시크릿 유출 금지 | ✅ | ① 실패 사유는 **우리 어휘만** — 빌드 출력을 `AgentRun.errorMessage` 에 싣지 않는다(그래도 `AgentRun.fail` 이 스크럽한다) ② 되먹임 피드백은 `StageResult.summary`(이미 스크럽 강제) · `DiffReview.findings`(이미 스크럽 강제)만 쓴다 ③ **지문은 해시만 보관**하고 원문을 들지 않는다 |
| S-5 대상 저장소 규약 우선 | ✅ | 🔴 **바퀴마다 통행증을 다시 받지 않는다** — 아래 D-5 에 근거를 적고 #23 으로 인계 |
| S-6 승인 지점 우회 금지 | ✅ | **이 이슈가 S-6 의 재시도 축 실행체다.** 상한은 도메인 상수가 위쪽 경계를 보고(`MAX_ALLOWED_ATTEMPTS`), 소진은 `FAILED`(종단)로 간다. 루프가 `selectByHuman`·`markPrCreated` 를 부르지 못하는 것을 ArchUnit 으로 고정 |

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리는가 | 처리 |
|---|---|---|
| **Q-6** 재시도 3회의 단위 | ✅ **확정됨** (#36·#39) | 정의를 코드로 옮기기만 한다. **재해석하지 않는다.** 개명(FR-8)이 Q-6 이 이 이슈로 지정한 잔여다 |
| **Q-4** 샌드박스 네트워크 | 🔴 **걸린다** | 아래 **가정 1**. 네트워크를 요구하는 테스트가 `StageOutcome.FAILED` 로 와서 **재시도 대상이 된다** — 3바퀴를 태운다. Q-4 가 「그 판정이 옳지 않다」고 이미 적어 둔 자리다. **이 이슈에서 고치지 않고 리스크로 노출**한다 (R-2) |
| Q-3 실행 프로필 분리 | — | 루프는 기존 `implement` 진입점 안에서 돈다. 새 진입점을 만들지 않는다 |
| Q-9 테스트 대역 | — | 이미 확정. 능력 페이크(`Fake*`)만 쓴다 — 샌드박스·LLM 은 전송 계약 층이 성립하지 않거나 이 이슈의 대상이 아니다 |

**가정**

1. **네트워크를 요구하는 대상 저장소 테스트는 「코드가 틀렸다」로 분류되어 재시도를 3회 태운다.**
   Q-4 의 알려진 한계이고 이 이슈가 그것을 **증폭**시킨다(1회 → 3회).
   틀리면 고칠 곳은 이 이슈가 아니라 **Q-4 의 종결 조건**이다 — `ContributionConstraints` 에서
   네트워크 없이 도는 부분집합을 뽑거나(#7), 제한된 네트워크 명령 타입을 만든다(S-3 개정).
   Q-4 이슈에 이 사실을 코멘트로 인계한다.
2. **지문(FR-5)이 발화하지 않을 수 있다.** 빌드 출력에 타임스탬프·소요 시간·절대 경로가 섞이면
   같은 오류라도 지문이 갈린다. 틀리면 고칠 곳은 `FailureFingerprint` 의 정규화인데,
   **실측 전에는 정규화 규칙을 만들지 않는다** — 그것은 거부목록이고
   `testing-philosophy.md` 가 「고칠 때마다 다음 형태가 나온다」로 금지한 축이다.

## 3. 스코프

| 도메인 | 변경 | 소유 판정 근거 |
|---|---|---|
| `candidate` | 신규 + 수정 | 재시도 카운터의 주체가 `ContributionCandidate.attempt` 이고, 루프가 조율하는 것(`CodingAgent`·`ChangeVerifier`·`DiffReviewer`)이 전부 `candidate/domain` 의 2층 능력이다 |
| `agent` | — | 🔴 **건드리지 않는다.** `AgentRun` 은 `candidate` 소유고, 1층 능력(`LanguageModel`·`CodeSandbox`)은 이미 있다 |

**도메인 간 계약** — 🔴 **새 능력 인터페이스를 선언하지 않는다.**

`CodingAgent`(#18) · `ChangeVerifier`(#19) · `DiffReviewer`(#20) 가 이미 선언돼 있고
**import 만 한다.** 이슈 코멘트 2 가 못 박은 규칙이고, `ChangeVerifier` 가 #18·#19 로 갈려
중복 선언된 것이 그 규칙이 생긴 이유다.

## 4. 기술 설계

### 핵심 결정

#### D-1. 🔴 재시도는 **화이트리스트**다 — 여집합으로 뒤집는다

`switch` 금지(이슈 코멘트 3)를 「`switch` 를 안 쓴다」로만 읽으면 부족하다. 진짜 요구는
**새 실패 종류가 생겼을 때 어디로 떨어지는가**다.

| 축 | 새 값이 생기면 | 판정 |
|---|---|---|
| 거부목록 — 「이것들은 재시도 안 함」 | 🔴 **조용히 재시도로 떨어진다** — 비용이 3배 |
| **화이트리스트 — 「이것들만 재시도」** | ✅ `FAILED` 로 떨어진다 — 사람에게 넘기는 신호 |

그래서 `RetryDecision` 은 **재시도가 의미 있는 경우를 열거하고 나머지를 전부 `FAIL`** 로 본다.
방향이 맞는지는 `external-deps.md` 의 기준으로 확인한다 — **틀렸을 때 되돌릴 수 있는 쪽**이
어디인가. 잘못 `FAIL` 하면 사람이 재분석하면 되고, 잘못 재시도하면 **LLM 과금과
샌드박스 90분이 나간다.**

```java
// candidate/domain/RetryDecision.java  (값)
public sealed interface RetryDecision {
    record Proceed()                              implements RetryDecision {}  // READY_FOR_PR
    record Retry(CodingFeedback feedback)         implements RetryDecision {}
    record Stop(String reason)                    implements RetryDecision {}  // FAILED — 우리 어휘
}
```

판정표 — **이 표 밖은 전부 `Stop`** 이다.

| 입력 | 판정 | 왜 |
|---|---|---|
| `report.passed()` && `review.passed()` | `Proceed` | 🔴 `passed()` 로만 본다. `!failed` 로 쓰면 `UNDETERMINED` 가 접힌다 |
| `report.hasUndetermined()` | **`Stop`** | 같은 입력에 같은 결과 — `StageOutcome.UNDETERMINED` javadoc |
| `!report.passed()` && `!hasUndetermined()` | `Retry(검증 피드백)` | 고칠 대상이 있다 |
| `review.verdict.warrantsRetry()` | `Retry(리뷰 피드백)` | `CHANGES_REQUESTED` 만 참 |
| `review.isUndetermined()` | **`Stop`** | 〃 |
| `DiffReviewRejectedException` | **`Stop`** | `Reason.retryable()` 이 지금 전부 false |
| `CodingOutOfPlanException` | **`Stop`** | 같은 계획·같은 프롬프트면 같은 결과 (#18 이 이미 그렇게 판정) |
| `VerificationSetupException` | **`Stop`** | 검증을 시작조차 못 했다 |
| `LlmException` · `SandboxException` | **`Stop`** | 🔴 전송 계층 축이 **이미 흡수**했다. 여기서 또 세면 축이 섞인다 |
| **그 밖의 무엇이든** | **`Stop`** | 화이트리스트의 정의 |

#### D-2. 에러 분석 = **피드백 값**이지 새 LLM 호출이 아니다

이슈가 말하는 「Error Analyzer」를 LLM 호출로 만들면 바퀴마다 호출이 하나 더 붙어
곱셈 예산이 **9회 → 18회**가 된다. PRD §17 다이어그램의 Error Analyzer 는
**분기점**이지 모델 호출이 아니다.

```java
// candidate/domain/CodingFeedback.java  (값)
public record CodingFeedback(Kind kind, List<String> points) {
    public enum Kind { COMPILE, TEST, DIFF, REVIEW }   // ← FR-3 의 「구분해 다른 프롬프트로」
}
```

- `COMPILE`·`TEST`·`DIFF` ← `VerificationStage` 에서 1:1 로 온다
- `REVIEW` ← `DiffReview.findings`
- 내용은 **이미 스크럽이 강제된 값**만 쓴다 (`StageResult.summary` · `DiffReview.findings`)

`CodingInput` 에 슬롯을 더한다 — `PlanningInput.withFeedback(verdict)` 가 이미 같은 모양이다.

```java
public record CodingInput(ImplementationPlan plan, RepositoryContext context,
                          ContributionConstraints constraints,
                          CodingFeedback feedback) {          // ← 신규. 첫 바퀴는 null
    public CodingInput withFeedback(CodingFeedback f) { … }
}
```

#### D-3. 🔴 같은 실패 2회 — **지문은 해시만 보관한다**

```java
// candidate/domain/FailureFingerprint.java  (값)
public record FailureFingerprint(String value) {   // SHA-256 앞 12자
    public static FailureFingerprint of(CodingFeedback feedback) { … }
}
```

- 🔴 **원문을 들지 않는다.** 들면 S-4 의 대상이 하나 늘고, 로그·`toString` 으로 샐 자리가 생긴다
- 이전 바퀴의 지문과 같으면 `Stop("같은 실패가 반복된다")`
- ⚠️ **발화하지 않을 수 있다** (가정 2). javadoc 에 그 한계를 **맨 앞에** 적는다 —
  `testing-philosophy.md` 「한계는 오탐이 아니라 **우회**를 적는다」

#### D-4. 🔴 후보 단위 실패 기록 — `AgentRun` 에 남긴다 (#18 이관)

#18 이 「사유가 로그에만 남는다」를 잔여로 남기고 이 이슈를 지목했다.

`AgentRunRecorder` 를 쓰지 않는다 — 그쪽은 **LLM 호출 1건**을 세는 축이고
(`started`/`succeeded`/`failed` 가 전부 `LlmUsage` 를 받는다), `VERIFY` 실패에는 사용량이 없다.

대신 `CandidateRetryWriter` 가 짧은 트랜잭션으로 직접 남긴다.

```java
AgentRun run = AgentRun.start(candidateId, Stage.VERIFY, attempt, clock);
run.fail(reason, clock);        // ← 🔴 유일한 대입 지점이 스크럽한다 (S-4 강제 지점)
```

`stage` 는 실패한 단계다. 같은 바퀴의 여러 행이 같은 `attempt` 를 갖는 것은
**이미 설계된 불변식**이다(`AgentRun.attempt` javadoc).

🔴 **세 단계 전부다 — `VERIFY` 만이 아니다.** 검토에서 잡힌 자리다.
지금 #18 의 코드는 `CodingOutOfPlanException` 과 일반 `RuntimeException`(CODE 실패)에
대해 **로그만 남기고 `AgentRun` 을 쓰지 않는다.** 그대로 두면 FR-6 이 반만 닫힌다 —
「`FAILED` 인데 왜인지 DB 에 없다」가 **CODE 단계에 그대로 남는다.**

| 실패 경로 | `AgentRun.stage` | 행을 남기나 |
|---|---|---|
| `CodingAgent` 가 계획 밖 경로 (`CodingOutOfPlanException`) | `CODE` | ✅ |
| 코딩 중 그 밖의 `RuntimeException` (워크스페이스·쓰기 실패 포함) | `CODE` | ✅ |
| 검증이 통과하지 못함 (`FAILED`·`UNDETERMINED`) | `VERIFY` | ✅ |
| 검증을 시작조차 못 함 (`VerificationSetupException`) | `VERIFY` | ✅ |
| 리뷰 거부 (`DiffReviewRejectedException`) · `UNDETERMINED` | `REVIEW` | ✅ |
| **상한 소진** (마지막 바퀴가 끝나고 더 돌 수 없음) | 🔴 **마지막 실패 단계** | ✅ — 아래 |
| `LlmException` · `SandboxException` (전송·환경) | 그 단계 | ✅ 사유에 「전송 계층 소진」을 적는다 |

⚠️ **상한 소진에 별도 행을 하나 더 만들지 않는다.** 마지막 바퀴의 실패 행이 이미 있고,
거기에 또 남기면 **같은 실패가 두 번 세어져 비용 집계가 어긋난다.** 상한 소진은
행이 아니라 **후보 상태(`FAILED`)와 `attempt` 값**이 말한다.

⚠️ `AgentRun.start` 는 `stage.requiresCandidate()` 를 보므로 `POLICY` 를 쓰지 않는 한
`candidateId` 가 필수다 — 루프는 항상 후보를 갖고 있어 문제없다.

#### D-5. 🔴 바퀴마다 통행증을 다시 받지 않는다 — **근거를 남긴다**

#18 이 비용(바퀴당 대외 호출 3배)을 근거로 그렇게 정했고, **이 이슈도 유지한다.**

⚠️ 그러나 **「#23 이 막는다」를 근거로 쓰지 않는다.** #23 이슈 본문이 그 오류를 이미
잡아 두었다 — *「존재하지 않는 방어를 가리킨 것」*. 사실은 이렇다.

> 루프가 도는 동안 대상 저장소가 AI 기여 금지로 바뀌어도 **지금 이 경로를 막는 것은 없다.**
> 창은 최대 3바퀴 × 30분 = 90분이고, 되돌릴 수 없는 쪽은 **규약 위반 PR 이 나가는 것**(S-5)이다.

- `retryImplementation` javadoc 에 이 사실을 적는다 (safety-reviewer 가 #24 리뷰에서 🟡 로 짚은 자리)
- **#23 이슈에 코멘트로 인계**한다 — 거기가 PR 생성 직전 1회 재확인을 판단하는 자리이고,
  루프 밖 1회라 비용 근거가 약하다(이슈 본문이 그렇게 적어 뒀다)

#### D-7. 🔴 `markPrCreated` 가드는 **허용목록이 아니라 여집합 + 미끼**다

계획 검토에서 잡힌 것이다. 「`ApprovalGateArchitectureTest` 에 더한다」로만 적으면
**기존 규칙의 형태를 그대로 쓰게 되는데, 그것이 여기서는 틀린다.**

기존 ①b(`사람_전이_메서드는_승인_UseCase_만_부른다`)는 **허용목록**이다 —
`selectByHuman`·`cancelSelection` 의 호출자를 `SelectCandidateUseCase` **하나로 고정**한다.
`markPrCreated` 에 그대로 붙이면 **「선정 UseCase 만 PR 을 만들 수 있다」**가 되어 의미가 뒤집힌다.
정당한 호출자는 **#23** 이고, 지금은 **아무도 아니다.**

| | 규칙 | #23 이 열 때 |
|---|---|---|
| ❌ 허용목록 재사용 | 「`SelectCandidateUseCase` 만」 | 의미가 이미 틀려 있다 |
| ✅ **여집합** | 「`markPrCreated` 를 부르는 타입이 **0개**」 | **이 테스트를 함께 고쳐야** 한다 — 그것이 강제 장치다 |

#### ✅ 그 장치가 실제로 발화했다 (2026-09-27)

**#23(PR #88)이 이 작업 중에 머지됐다** — §7 R-6 이 적어 둔 위험 그대로다.
리베이스하자 예상대로 둘이 깨졌다.

| 깨진 것 | 왜 |
|---|---|
| `AutoPrCreateProbe` 컴파일 | #23 이 `markPrCreated(PullRequest, Clock)` 으로 시그니처를 바꿨다 (#18 이 예고한 그대로) |
| 🔴 **여집합 규칙** | #23 이 `CandidatePrWriter` 라는 **정당한 호출자**를 만들었다 |

🔴 **테스트를 지우고 열지 않았다.** 규칙을 「0개」에서 **「`CandidatePrWriter` 하나만」**으로
조였고, 그 한 줄이 리뷰에 보인다. #23 은 이 가드를 스스로 만들지 않았으므로
**이 PR 이 그 자리를 메운다.**

⚠️ **지금은 허용목록이 맞다** — 정당한 호출자가 **실재**하기 때문이다.
위 표의 「허용목록 재사용이 틀렸다」는 **누구를 지목하느냐**의 문제였지 형태의 문제가 아니었다.

`ForkPublishArchitectureTest`(S-1)가 이미 같은 축이고, `CandidateApprovalApiTest`의
「착수·PR 생성 엔드포인트는 없다」가 같은 강제 장치다.

🔴 **0건이어도 초록이 되지 않게 미끼를 둔다.** 위반이 0건인 상태에서는 판정기가 항상
`false` 를 돌려줘도 초록이다(`testing-philosophy.md` 요구 2). `support/testing/probe` 에
**상시 양성 표본**을 두고 검사기가 그것을 잡는지 단언한다 — 이름이 `Test` 로 끝나지 않고
`@Test` 메서드도 없어 **실제로 실행되지는 않는다**(#43 이 세운 관행 그대로).

#### D-6. 개명은 **이름만** 바꾼다

`agent.execution.max-retries` → `agent.execution.max-attempts`, `maxRetries()` → `maxAttempts()`.

🔴 **값(3)도 비교(`attempt >= maxAttempts`)도 바꾸지 않는다.** Q-6 이 경고한 그대로 —
*「이름만 보고 off-by-one 으로 판단해 비교를 고치면 곱셈 예산이 함께 무효가 된다」*.
커밋 메시지와 PR 본문에 「이름만 바꿨다」를 명시한다.

### 변경 파일

| # | 경로 | 레이어 | 신규/수정 | 내용 |
|---|------|--------|----------|------|
| 1 | `candidate/application/ExecutionProperties.java` | application | 수정 | **개명** — `maxRetries` → `maxAttempts` · javadoc 의 「고치지 않는다」 절 제거 |
| 2 | `src/main/resources/application.yml` | — | 수정 | `max-retries: 3` → `max-attempts: 3` (+ 주석 3곳) |
| 3 | `candidate/domain/RetryDecision.java` | domain | **신규** | sealed — `Proceed`/`Retry`/`Stop`. D-1 |
| 4 | `candidate/domain/CodingFeedback.java` | domain | **신규** | `Kind` 4종 + 지적 목록. D-2 |
| 5 | `candidate/domain/FailureFingerprint.java` | domain | **신규** | SHA-256 앞 12자. **원문 미보관**. D-3 |
| 6 | `candidate/domain/RetryPolicy.java` | domain | **신규** | 순수 판정 — 보고서·리뷰·예외 → `RetryDecision`. 🔴 화이트리스트 |
| 7 | `candidate/domain/CodingInput.java` | domain | 수정 | `feedback` 슬롯 + `withFeedback` |
| 8 | `candidate/domain/ContributionCandidate.java` | domain | 수정 | `retryImplementation` javadoc 에 D-5 명시 (동작 변경 없음) |
| 9 | `candidate/application/ImplementCandidateUseCase.java` | application | 수정 | 🔴 **1바퀴 → 루프.** REVIEW 배선. `Proceed` → `READY_FOR_PR` |
| 10 | `candidate/application/CandidateRetryWriter.java` | application | **신규** | 짧은 트랜잭션 — `retry`/`readyForPr`/`failWithReason`. D-4 |
| 11 | `candidate/application/CandidateImplementationWriter.java` | application | 수정 | `maxAttempts` 개명 반영 · 🔴 `fail(candidateId, reason)` 을 **`CandidateRetryWriter` 로 이관**하고 잔여 javadoc 정정(#21 이 닫았다) |
| 12 | `candidate/adapter/out/llm/LlmCodingAgent.java` | adapter/out | 수정 | `feedback` 을 프롬프트에 싣는다 (FR-3) |
| 13 | `candidate/domain/ApprovalGateArchitectureTest.java` | test | 수정 | 🔴 **여집합** 규칙 — `markPrCreated` 호출자 0개 (D-7) |
| 14 | `support/testing/probe/AutoPrCreateProbe.java` | test | **신규** | 🔴 **미끼** — 13 이 0건에서도 무는지. `Test` 로 끝나지 않고 `@Test` 없음 |

### 체크리스트 답변

| # | 항목 | 답 |
|---|------|---|
| 1 | 소유 도메인 | `candidate` — 카운터 주체가 `ContributionCandidate.attempt` |
| 2 | 레이어 배치 | 판정은 **domain**(`RetryPolicy` 순수 함수), 조율은 **application**(루프), 프롬프트는 **adapter/out** |
| 3 | 능력 인터페이스 | 🔴 **불필요** — 셋 다 이미 선언돼 있다. import 만 한다 |
| 4 | 🔴 트랜잭션 안 대외 호출 없음 | ✅ 루프 전체가 트랜잭션 밖. `assertNoTransaction()` 유지. 전이·기록만 `CandidateRetryWriter`(별도 빈 — self-invocation 회피) |
| 5 | 상태 전이 영향 | 🔴 **전이 규칙을 바꾸지 않는다.** `TESTING·REVIEWING → IMPLEMENTING`·`REVIEWING → READY_FOR_PR` 가 **이미 열려 있다**(`CandidateStatus`). 이 이슈는 **호출자를 만든다** |
| 6 | 멱등성 | 루프는 `implement` 호출 1건 안에서 돈다. 중복 방어는 `@Version`(낙관적 락)이 이미 한다 — 동시 `implement` 둘은 두 번째가 전이에서 깨진다 |
| 7 | `Clock` 주입 | ✅ 전이·`AgentRun` 전부 주입된 `Clock` |
| 8 | 🔴 안전 경계 | §2 참조 |

### 데이터 모델

**해당 없음 — 스키마를 바꾸지 않는다.** `agent_run`·`contribution_candidate.attempt` 가
이미 있고 마이그레이션이 필요한 변경이 없다.

### API 계약

**해당 없음 — 엔드포인트를 추가하지 않는다.** `POST /api/candidates/{id}/implement` 는
#18 이 이미 열었고, 루프는 그 안에서 돈다. 🔴 **세 번째 게이트(`pull-request`)는 열지 않는다** — #23.

## 5. 구현 순서

### 실행 모드: sequential

**판정 근거** — `ImplementCandidateUseCase`(파일 9)를 Stage 3·4 가 함께 고치고,
`ExecutionProperties`(파일 1)를 Stage 1·4 가 함께 본다. 파일이 겹치므로 병렬이 성립하지 않는다.

| Stage | 내용 | 선행 조건 | 파일 |
|-------|------|----------|------|
| 1 | **개명** — 이름만. 값·비교 불변 | 없음 | 1, 2 + 문서 3 |
| 2 | **도메인 값 + 판정** — `RetryDecision`·`CodingFeedback`·`FailureFingerprint`·`RetryPolicy` | 없음 | 3~6 |
| 3 | **피드백 되먹임** — `CodingInput` 슬롯 + 프롬프트 | Stage 2 | 7, 12 |
| 4 | **루프** — UseCase 를 루프로 · REVIEW 배선 · `CandidateRetryWriter` | Stage 1·2·3 | 8~11 |
| 5 | **테스트 + 돌연변이 검증** | Stage 4 | §6 |

## 6. 테스트 계획

| # | 레벨 | 대상 | 검증 내용 |
|---|------|------|----------|
| 1 | 유닛 | `RetryPolicyTest` | 🔴 **판정표 전수.** `StageOutcome.values()` × `ReviewVerdict.values()` **전 조합**을 돌려 화이트리스트 밖이 전부 `Stop` 인지 |
| 2 | 유닛 | 〃 **입력 도달**(요구 4) | `ReviewVerdict.values().length` · `StageOutcome.values().length` 를 **모수로 단언**한다. 값이 늘면 이 테스트가 먼저 빨개져 판정표를 다시 보게 된다 |
| 3 | 유닛 | `FailureFingerprintTest` | 같은 피드백 → 같은 지문 / 다른 피드백 → 다른 지문 / 🔴 **`toString` 에 원문이 없다** |
| 4 | 유닛 | `ContributionCandidateTest` 보강 | `attempt` 가 바퀴마다 1씩 · **3바퀴째 `retryImplementation` 이 `FAILED`** · `REVIEWING → READY_FOR_PR` |
| 5 | 통합(페이크) | `ImplementCandidateUseCaseTest` | ① 2바퀴째 통과 → `READY_FOR_PR` ② 3바퀴 전부 실패 → `FAILED` ③ `UNDETERMINED` → **1바퀴만 돌고** `FAILED` ④ 같은 지문 2회 → **조기 중단** ⑤ `CHANGES_REQUESTED` → 다음 바퀴 프롬프트에 `Kind.REVIEW` 피드백이 실린다 |
| 6 | 통합 | 〃 | 🔴 **바퀴 수가 실제로 셋인지 페이크의 호출 기록으로** 센다. 상태만 보면 「1바퀴 돌고 실패」와 구분되지 않는다 |
| 7 | 유닛 | `ExecutionPropertiesTest` | 🔴 **개명한 키가 `application.yml` 과 맞는지.** 어긋나면 기동이 깨지는데 유닛만으로는 안 보인다 — `@ConfigurationProperties` 바인딩을 실제로 태운다 |
| 8 | ArchUnit | `ApprovalGateArchitectureTest` 보강 | 🔴 **여집합** — `markPrCreated` 를 부르는 타입이 `com.ossagent` 전체에 **0개**(D-7 · S-2). 메서드 **참조**(`::`) 형태도 본다(#73 이 잡은 구멍) |
| 8b | ArchUnit **물림** | `AutoPrCreateProbe` 미끼 | 🔴 **0건이어도 초록이 되지 않게.** 미끼를 검사기가 **무는지** 단언한다 — 없으면 판정기가 항상 `false` 여도 통과한다(요구 2) |
| 9 | 영속 | `CandidateRetryWriterTest` | 🔴 **CODE·VERIFY·REVIEW 세 단계 전부** `AgentRun` 실패 행이 **DB 재조회로** 보이는가(D-4 표) · `errorMessage` 가 스크럽됐는가(S-4) · **상한 소진에 행이 하나 더 생기지 않는가** |

**대외 호출 대체** — Q-9 확정대로 **능력 페이크만** 쓴다.
`FakeCodingAgent`·`FakeChangeVerifier`·`FakeDiffReviewer` 를 능력과 같은 패키지의 `src/test` 에 둔다.
🔴 **실패 모드를 재현할 수 있어야 한다** — 바퀴별로 다른 결과를 돌려주고 호출 기록을 남긴다.
(「항상 성공만 반환하는 페이크」로는 이 이슈의 게이트를 **하나도** 검증하지 못한다.)

### 🔴 돌연변이 검증 (S-6 대상이라 필수)

`testing-philosophy.md` 가 안전 경계 가드에 요구하는 것이다. **「무엇을 빼니 몇 건이 빨개졌다」를 PR 본문에 숫자로 적는다.**

| # | 제거할 것 | 기대 |
|---|---|---|
| 1 | `retryImplementation` 의 `attempt >= maxAttempts` 비교 | 상한 소진 테스트가 빨개진다 |
| 2 | `RetryPolicy` 의 `hasUndetermined()` 분기 | 「`UNDETERMINED` 는 1바퀴」가 빨개진다 |
| 3 | 지문 비교 | 조기 중단 테스트가 빨개진다 |
| 4 | 화이트리스트를 거부목록으로 뒤집기 | 전수 조합 테스트가 빨개진다 |
| 5 | `markPrCreated` 여집합 규칙 (D-7) | 🔴 **미끼 물림 단언이 빨개진다.** 운영 코드에 위반이 0건이라 이것 말고는 잴 것이 없다 |

⚠️ **제거 지점이 측정 대상보다 「위」면 아무것도 재지 못한다**(#64 의 함정).
전부 「빨강」으로 쏠리면 잘 막힌 것이 아니라 **엉뚱한 것을 쟀다고 먼저 의심한다.**

## 7. 리스크

| # | 리스크 | 영향 | 대응 |
|---|--------|------|------|
| R-1 | **비용이 3배가 된다** | LLM 호출 9회 + 샌드박스 90분/후보 | 곱셈 예산을 NFR-1 에 명시. `ossagent.llm.cost`(#71)가 이미 관측한다 |
| R-2 | 🔴 **Q-4 — 네트워크 요구 테스트가 3바퀴를 태운다** | 정상 코드가 `FAILED` 로 떨어지고 비용은 3배 | **이 이슈에서 고치지 않는다**(S-3 개정 또는 #7 이 필요). Q-4 이슈에 코멘트로 인계 |
| R-3 | **루프 전체 상한이 없다** | 최악 90분 + LLM 대기. `implement` 는 비동기가 아니다 | 🔴 **이 이슈에서 만들지 않는다** — 바퀴별 상한(1800s)은 이미 있고, 전체 상한은 **실행 프로필 분리(Q-3)와 함께** 판단할 것이다. PR 본문에 적는다 |
| R-4 | 지문이 발화하지 않는다 | FR-5 가 사실상 죽는다 | 가정 2. javadoc 맨 앞에 한계를 적고, **실측 전 정규화하지 않는다** |
| R-5 | 통행증이 루프 중 낡는다 (D-5) | 🔴 규약 위반 PR (S-5) — 되돌릴 수 없다 | **막지 않는다.** 근거를 javadoc 에 남기고 #23 에 인계. 「막힌다」고 적지 않는다 |
| R-6 | #23(PR #88)과 `ContributionCandidate`·`CandidateStatus` 에서 충돌 | main 이 빨개진다 (#61 의 재현) | 머지 직전 최신 `main` 으로 재빌드(#61 의 B안). PR #88 이 먼저 머지되면 리베이스 |

**대외 호출 실패 시나리오**

| 시나리오 | 기대 동작 |
|---|---|
| GitHub 레이트리밋 소진 | 실패가 아니라 **지연**. 루프 카운터를 태우지 않는다 |
| LLM 타임아웃 | 🔴 **전송 계층 축이 흡수**(`agent.llm.max-retries`). 소진되면 `LlmException` → `Stop` |
| 샌드박스 OOM·타임아웃 | `StageOutcome.FAILED` → **재시도 대상**. 컨테이너 정리는 `SandboxResult.cleanedUp` 이 보고 |
| 샌드박스 자체가 못 뜸 | `SandboxException` → `Stop` (작업이 아니라 환경 문제) |

## 8. 복잡도

| Stage | 파일 수 | 복잡도 |
|-------|--------|--------|
| 1 개명 | 2 + 문서 3 | 낮음 (기계적이나 **문서 3곳 동시**가 함정) |
| 2 도메인 값·판정 | 4 | 중간 (화이트리스트 축이 핵심) |
| 3 피드백 되먹임 | 2 | 낮음 |
| 4 루프 | 4 | **높음** — 이 이슈의 본체 |
| 5 테스트 | ~8 | **높음** — 돌연변이 4건 포함 |

## 9. 문서 동기화 대상

| 대상 | 필요 | 이유 |
|---|---|---|
| `codemaps/architecture.md` | — | 도메인·경계 변경 없음 |
| `codemaps/data.md` | — | 스키마 변경 없음 |
| `codemaps/domain.md` | ✅ | 재시도 전략 절 — 「개명은 #21 에서」(383~385줄)가 닫힌다 · 불변식 ⑧ 판정 필드 확정 |
| `.env.example` | — | 새 환경변수 없음 (`agent.execution.*` 는 yml 전용) |
| `README.md` | — | 디렉토리 구조 불변 |
| `rules/context/glossary.md` | ✅ | `RetryDecision`·`CodingFeedback`·`FailureFingerprint` 3개 · `DiffReviewer` 행의 「임계는 #21」 정정 |
| `rules/context/open-questions.md` | ✅ | **Q-6 의 개명 잔여가 닫힌다.** ⚠️ Q-6 자체는 이미 확정이므로 **잔여만** 정정한다 |
| `rules/conventions/architecture.md` | ✅ | §4 재시도 3축 표의 키 이름 (`max-retries` → `max-attempts`) |
| `rules/context/external-deps.md` | ✅ | 같은 키 이름 2곳 |

## 10. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-27 | smileboy0014 | 초안 — #18 머지(`70167f9`) 직후 기준 |
| 2026-09-27 | smileboy0014 | 검토 반영 2건 — ① **D-7 신설**: `markPrCreated` 가드를 허용목록이 아니라 **여집합 + 미끼**로 (기존 규칙 형태를 재사용하면 의미가 뒤집힌다) ② **D-4 확장**: 실패 경로별 `AgentRun` 표 — `VERIFY` 만 적어 두면 **CODE 단계에서 FR-6 이 반만 닫힌다** |
