# PLAN-20: AI diff 리뷰 — 통과한 diff 를 LLM 이 다시 본다

> 이슈: [#20](../../issues/20) · 라벨 `domain:agent` · 마일스톤 Phase 2
> 선행: #19 (검증 파이프라인 — **진행 중**) · #10 (LLM 어댑터) · #16 (규약 제약) · #28 (스크럽)
> 안전 경계: 🔴 **S-4** (diff 를 프롬프트에 넣는다) · **S-2**·**S-6** (리뷰 통과가 제출이 아니다) · **S-5** (규약을 입력으로 쓴다)

---

## 0. 이 이슈가 무엇인가

**빌드·테스트를 통과해도 엉뚱한 구현일 수 있다.** 이슈가 요구한 것과 다른 것을 고쳤거나,
범위를 넘었거나, 저장소 관습을 어겼거나, 테스트가 형식만 갖춘 경우다. 결정적 게이트
(컴파일·테스트)가 못 잡는 종류이고, 그래서 LLM 리뷰가 파이프라인의 마지막 거름망이다.

PRD §30 의 「품질의 축은 **나쁜 결과를 걸러내는가**」가 직접 걸려 있는 단계다.

### 0.1 🔴 선행 #19 가 진행 중인데 왜 지금 시작하나

#19 가 주는 것은 **검증할 대상**(테스트를 통과한 워크스페이스·diff)이지 **리뷰할 능력**이 아니다.
능력 쪽은 이미 전부 있다.

| 필요한 것 | 어디 |
|---|---|
| LLM 호출 | `LanguageModel` + `RecordingLanguageModel`(#10) ✅ |
| 저장소 규약 | `AnalyzeRepositoryPolicyUseCase.constraintsOf` → `ContributionConstraints`(#16, 머지됨) ✅ |
| 이슈 원문 | `AnalyzableIssue`(#11) ✅ |
| 실행 기록 | `AgentRunRecorder` + `LlmCallSite.REVIEW`(#10) ✅ — **열거값이 이미 있다** |
| 저장 자리 | `generated_change.review_result` (V2) ✅ |

즉 **능력 + 값 타입 + 기록 메서드**는 지금 지을 수 있고, **배선만** #19·#21 에 남는다.
이것은 #68 에서 「지문만 기록하면 죽은 코드가 된다」고 판단했던 것과 **다른 상황**이다 —
저쪽은 비교할 두 번째 관측이 영영 안 생겼지만, 여기는 **호출자가 곧 온다**(#19 가 작업 중).

### 0.2 🔴 이 PR 이 하지 않는 것 — 경계를 먼저 못 박는다

> **능력 인터페이스는 구현을 싣는 PR 이 선언한다. 호출자는 import 만 한다.**
>
> #19 가 `ChangeVerifier` 를 #18 과 **중복 선언**하게 된 것은 구현(#19)과 호출자(#18)가
> 다른 PR 로 갈린 예외 상황이라서다. 이 PR 은 선언·값 타입·구현을 **한 PR 에 다 싣는다.**
> 🔴 **#21 이 `DiffReviewer` 를 다시 선언하면 그것이 틀린 것이다** — 이슈 #21 에 남긴다.

`agent` 도메인에 **두 세션이 동시에 들어와 있다**(#18 코딩 · #19 검증). 겹치면 서로를 덮는다.

| 안 한다 | 누구 몫 |
|---|---|
| `agent/**` 의 어떤 파일도 건드리지 않는다 | #18 · #19 |
| `GeneratedChange` **생성 팩토리** | #18 (기록 메서드만 더한다) |
| 상태 전이(`REVIEWING` → `READY_FOR_PR`) | #19 · #21 |
| `attempt` 카운터 관리 | #21 |
| **리뷰 실패 시 코딩 단계로 되돌림** | 🔴 **#21** — 이슈 완료 조건에 있지만 재시도 루프의 일이다 |
| 파이프라인 호출 지점 | #19 |

### 0.3 🔴 FR-4(저장)를 이 PR 에서 하지 않는다 — #19 와 합의

`GeneratedChange` 는 지금 **`@Getter` + protected no-args 뿐**이고 **생성 팩토리가 없다.**
행을 만들 수 없으니 기록 메서드를 더해도 **부를 대상이 없다.**

그 팩토리는 #18 이 소유하기로 세 세션이 합의했다 — 양쪽이 각자 팩토리를 만들면
**생성 경로가 둘**이 되고, Q-7 이 `@Builder`·`@AllArgsConstructor` 를 금지한 근거
(「경로가 늘면 불법 상태를 만들 수 있다」)에 정면으로 걸린다.

#19 도 같은 이유로 `recordVerification` 을 미뤘다. **같은 판단을 따른다.**

| 이 PR | #18 이후 |
|---|---|
| 능력 + 값 타입 + LLM 어댑터 | `GeneratedChange.recordReview(DiffReview)` |

🔴 **그래서 등록표의 `GeneratedChange.reviewResult` 행을 `VALUE_TYPE` 으로 올리지 않는다.**
`DiffReview` 가 스크럽을 강제하는 것은 사실이지만, **그 값이 그 컬럼에 앉는 경로가 아직 없다.**
올려 두면 「가드가 있다」가 「이 입력에 닿는다」로 읽힌다 — #73 이 정확히 그 혼동이다.
**`PENDING` 을 유지하고 사유만 정확히 고친다.**

### 0.4 계약 표면 변경

| 표면 | 변경 |
|---|---|
| `candidate/domain/DiffReviewer` | **신설** — 능력 인터페이스 |
| `candidate/domain/DiffReview` | **신설** — 값 타입 (스크럽 강제) |
| ~~`GeneratedChange.recordReview(...)`~~ | 🔴 **#18 이후** — §0.3 |
| `ExternalTextScrubRegistryTest` | `DiffReview.summary`·`findings` **신규 `VALUE_TYPE`** · `GeneratedChange.reviewResult` 는 **`PENDING` 유지**(담당·사유만 정정) |

**DB 스키마 변경 없음** — `review_result` 컬럼은 V2 부터 있다. 마이그레이션이 없다.

⚠️ 등록표 그 행의 담당이 **`#19` 로 잘못 적혀** 있다. #19 세션과 확인했고 `reviewResult` 는 이 이슈다.

---

## 1. 요구사항

### FR — 이슈 완료 조건과 1:1

| # | 요구 | 이 PR |
|---|---|---|
| FR-1 | diff + 이슈 원문 + 저장소 규약을 넣고 리뷰 | ✅ |
| FR-2 | 판정 기준 정의 — 요구 충족 / 범위 초과 / 관습 위반 / 테스트 적절성 | ✅ 네 축을 **타입으로** |
| FR-3 | 실패 시 코딩 단계로 되돌림 | ❌ **#21** (§0.2) |
| FR-4 | 리뷰 결과를 `generated_change.review_result` 에 저장 | 🔴 **이 PR 에서 하지 않는다** — §0.3 |
| FR-5 | **리뷰 통과가 곧 제출이 아니다** — `READY_FOR_PR` 까지만 | ✅ 전이를 만들지 않는 것으로 |
| FR-6 | `AgentRun` 기록 | ✅ — 아래 |

🔴 **FR-6 은 내가 코드를 쓰지 않는 것으로 달성된다.** `RecordingLanguageModel` 이 노출되는
유일한 `LanguageModel` 빈이라 **기록을 건너뛸 경로가 없다.** 내가 할 일은
`AgentRunContext(candidateId, REVIEW, attempt)` 를 **정확히 넘기는 것**뿐이다.
직접 `AgentRunRecorder` 를 부르면 **이중 기록**이 된다.

### NFR

| # | 요구 |
|---|---|
| NFR-1 | 🔴 diff 를 **자르지 않는다.** 상한 초과는 리뷰 실패다 — §2.2 |
| NFR-2 | 관찰값만 돌려준다. **임계 판정은 호출자** — `IssueAnalyst` 선례 |
| NFR-3 | 대외 호출은 트랜잭션 밖 |
| NFR-4 | LLM 응답을 신뢰하지 않는다 — 스키마 검증 + 스크럽을 **생성자가 강제** |

---

## 2. 게이트 판정

### 2.1 🔴 S-4 — 이 PR 의 주된 위험이다

**diff 는 대상 저장소의 코드 조각 그 자체**다. 저장소가 시크릿을 커밋해 뒀으면 diff 에 실려 온다.
그리고 이 단계는 그것을 **모델 제공자에게 보낸다.**

| 경로 | 방어 |
|---|---|
| diff → LLM 프롬프트 | `PromptScrubber` — 어댑터 생성자가 `scrubber == null` 을 **거부**한다(#10). 새로 만들 것이 없고, **`LanguageModel` 을 거치는 것 자체가 방어**다 |
| LLM 응답 → `review_result` (DB) | 🔴 **여기가 새로 생기는 유출구다.** 리뷰가 diff 를 인용하면 위 위험이 **DB 로 복제**된다 |
| `review_result` → 하류 | #13 조회 API · PR 본문(#23)까지 갈 수 있다 |

**응답 쪽 방어를 이 PR 이 만든다** — `DiffReview` compact 생성자가 `TokenRedactor.redact` 를
강제한다. `IssueAnalysis`(#11) · `ScrubbedRules`(#7) 와 같은 수법이다.

⚠️ **그렇다고 등록표의 `reviewResult` 행을 `VALUE_TYPE` 으로 올리지는 않는다** — §0.3.
값 타입이 스크럽을 강제하는 것은 사실이지만 **그 값이 그 컬럼에 앉는 경로가 아직 없다.**
등록표에 새로 올라가는 것은 `DiffReview.summary`·`DiffReview.findings` 두 행이다.

🔴 **「강제 지점」이 아니라 「값 타입」인 이유** — `String` 을 받는 생성 경로를 두지 않으면
스크럽을 건너뛸 방법이 없다. 대입 지점을 세는 방식(`FORCED_POINT`)은 **새 대입 지점이
생기면 조용히 뚫린다.**

⚠️ **#73 의 교훈을 적용한다** — 「가드가 있다 ≠ 가드가 이 입력에 닿는다」.
`DiffReview` 를 거치지 않고 `reviewResult` 에 값이 들어가는 경로가 **없는지**를 테스트한다.

### 2.2 🔴 diff 상한 — 자르지 않는다

PRD §12 가 「저장소 전체를 넘기지 않는다」고 하고, diff 는 수십 KB 가 될 수 있다.

**#7 이 규약 문서에서 내린 판단을 그대로 쓴다** — 「자르지 않는다. 잘린 뒷부분에 금지 문구가
있었는지 판정할 방법이 없다」. 여기서도 **잘린 diff 를 리뷰하면 「안 본 부분에 문제가 있었을
수 있다」**가 되고, 그것은 리뷰가 아니라 리뷰의 시늉이다.

| 상황 | 처리 |
|---|---|
| diff 가 상한 이하 | 리뷰한다 |
| 🔴 diff 가 상한 초과 | **리뷰 실패**(예외). 절단도 부분 리뷰도 하지 않는다 |

⚠️ 이것은 **게이트를 느슨하게 하지 않는 쪽**이다 — 실패하면 후보가 통과하지 못한다.
「너무 큰 변경은 애초에 후보가 아니다」라는 필터(#9 의 「대규모 아키텍처 변경」)와 같은 방향이다.

#### 🔴 그런데 이 예외는 **재시도 대상이 아니다** — `UNDETERMINED` 와 같은 이유다

같은 diff 는 매 바퀴 같은 예외를 낸다. 재시도하면 **고칠 수 없는 것에 Q-6 예산 3바퀴를
통째로 태우고** 후보가 코드 문제 없이 `FAILED` 로 떨어진다 — §3.2 가 `UNDETERMINED` 를
만든 근거가 여기에 **그대로** 적용된다.

그래서 `DiffReviewRejectedException` 에 **사유 enum**(`TOO_LARGE` · `SCHEMA`)을 싣는다.
둘 다 재시도해도 같으므로 **#21 이 그것으로 분기할 수 있어야** 한다.
⚠️ 이것은 전송 계층 실패(`LlmException`)와 다르다 — 저쪽은 재시도가 의미 있다.

### 2.3 S-2 · S-6 — 리뷰 통과가 제출이 아니다

| 지킬 것 | 어떻게 |
|---|---|
| 리뷰 통과가 PR 생성으로 이어지지 않는다 | 🔴 **상태 전이를 만들지 않는다.** 이 PR 에 `READY_FOR_PR` 전이도 PR 생성 호출도 **없다** |
| 임계를 자동화가 갖지 않는다 | 관찰값만 돌려준다(NFR-2). 「몇 점이면 실패인가」를 이 PR 이 정하지 않는다 |

⚠️ **「리뷰가 통과시켰다」를 승인으로 쓰지 않는다.** S-6 의 승인 지점은 셋(선정·착수·PR 생성)이고
AI 리뷰는 그중에 없다 — PRD v1.2 §24 가 그렇게 그려져 있다(#30).

### 2.4 S-5 — 규약을 리뷰 입력으로 쓴다

「저장소 관습 위반」을 판정하려면 규약이 필요하다. `ContributionConstraints`(#16)를 입력으로 받는다.

🔴 **`ContributionConstraints` 는 게이트가 아니다** — 그 타입의 javadoc 이 못 박아 둔 그대로다
(「제약을 줄 때 허용 여부도 함께 주면 게이트가 부산물이 된다」). 여기서도 **「이렇게 해라」로만**
쓰고 「해도 된다」로 쓰지 않는다. 통행증(`PolicyClearance`)은 이 단계에 오지 않는다 —
**이미 `startImplementing` 에서 확인됐다.**

⚠️ `ContributionConstraints.unknown()` 이 올 수 있다(정책 행이 없을 때). 그때는 「관습 위반」
축을 **판정하지 못한다** — 모르는 것을 「위반 없음」으로 적지 않는다. §4.3.

### 2.5 미접촉

| | 근거 |
|---|---|
| S-1 | push·Fork·remote 를 건드리지 않는다 |
| S-3 | 샌드박스를 호출하지 않는다. diff 는 **이미 만들어진 문자열**로 받는다 |

### 2.6 미결 대조

| 항목 | 접촉 | 처리 |
|---|---|---|
| **Q-6** (재시도 단위) | `attempt` 를 인자로 받는다 | ✅ **확정됨** — `CODE→VERIFY→REVIEW` 한 바퀴가 1. 내가 정하지 않고 받는다 |
| Q-9 (테스트 대역) | 3계층 | 능력 페이크 + `MockRestServiceServer` 층은 #10 이 이미 덮는다. 새 전송 없음 |
| Q-11 | LLM SDK | 새 대외 의존 없음 |
| 🔵 **미결 아님 — 이슈가 안 정한 것** | 「몇 점이면 실패인가」 | 🔴 **정하지 않는다.** NFR-2 로 호출자에게 남기고 그 근거를 적는다 |

#### 🔴 임계를 갖게 될 #21 에게 남기는 제약

임계를 **설정값(`application.yml`)으로 두지 않는다.** Q-6 이
`ContributionCandidate.MAX_ALLOWED_ATTEMPTS` 를 **도메인 상수**로 둔 이유와 같다 —
「상한을 올리려면 도메인 코드를 고쳐야 하고 **그것이 리뷰에 보인다**」.

리뷰 임계가 프로퍼티면 게이트가 배포 설정 한 줄로 느슨해질 수 있다. S-2 의 「draft 플래그를
두면 언젠가 켜진다」와 같은 모양이다.

🔴 **다만 이 주장을 「금지」로 내리지 않는다 — 선례가 반대편에 있다.**
`candidate/application/IssueAnalysisProperties.minConfidence` 가 바로 「미만이면 `REJECTED`」인
**판정 임계이고 이미 설정값**이다. 그것을 언급 없이 제약으로 내리면 #21 이 기존 코드와
충돌한다.

**#21 에게 남기는 것은 금지가 아니라 「판단하고 근거를 남겨라」다.**

| | |
|---|---|
| 도메인 상수 쪽 근거 | Q-6 `MAX_ALLOWED_ATTEMPTS` — 「올리려면 코드를 고쳐야 하고 그것이 리뷰에 보인다」 |
| 설정값 쪽 근거 | `minConfidence` 선례. 그리고 **리뷰 뒤에도 사람 게이트(`pull-request`)가 남아** 있어 임계 하나가 곧바로 유출은 아니다 |

⚠️ **이 PR 의 `DiffReviewProperties` 는 임계가 아니다** — diff 상한·출력 토큰 상한은
**비용 한도**이지 판정 기준이 아니다. 🔴 **그 구분을 클래스 javadoc 에 적는다** —
계획서에만 있으면 다음 사람은 클래스만 본다.



---

## 3. 기술 설계

### 3.1 필수 체크리스트

| # | 항목 | 답 |
|---|---|---|
| 1 | 소유 도메인 | **`candidate`** — `GeneratedChange` 가 거기 있고, `IssueAnalyst`(2층 LLM 능력)의 선례가 그렇다 |
| 2 | 레이어 | 능력·값 타입 = `candidate/domain` · 구현 = `candidate/adapter/out/llm` |
| 3 | 능력 인터페이스 | ✅ **`DiffReviewer`** (능력 이름) / **`LlmDiffReviewer`** (기술 이름) — 규율 ③ |
| 4 | 🔴 트랜잭션 밖 | 이 PR 은 UseCase 를 만들지 않는다. 능력 구현은 트랜잭션을 열지 않고, **호출자가 밖에서 부른다**는 것을 javadoc 에 명시 |
| 5 | 상태 전이 | **없다** — §2.3 |
| 6 | 멱등성 | 리뷰는 재시도마다 **새로 한다**. LLM 이 비결정적이라 같은 값을 기대하지 않는다. `recordReview` 는 덮어쓴다 — 바퀴마다 최신 리뷰가 맞다 |
| 7 | `Clock` | **해당 없음** — 시각 판단이 없다. `GeneratedChange` 에 리뷰 시각 컬럼이 없고 만들지 않는다(마이그레이션 없음) |
| 8 | 안전 경계 | §2 |

### 3.2 `DiffReview` — 판정 네 축을 타입으로

이슈가 요구한 네 축(FR-2)을 **문자열 설명이 아니라 필드로** 둔다. 문자열이면 모델이
무엇을 답했는지 세지 못하고, 하류가 파싱하게 된다.

#### 🔴 `ReviewVerdict` 는 **셋**이다 — 초안의 둘은 결함이었다

초안은 `PASS · CHANGES_REQUESTED` 둘이었다. **#19 가 같은 함정에 먼저 빠졌다** —
계획서 rev.2 가 「판정 불가는 실패로 친다」였고 그대로 짰으면 **모든 후보가 코드 문제 없이
`FAILED`** 였다(로그가 일상적으로 상한에 걸리는데 그것을 실패로 접었다).
**방어가 스스로를 잠그는 구조**다.

LLM 리뷰에 그대로 온다 — 모델이 판정을 거부하거나, 응답이 스키마를 만족하지만 내용이
「판단할 수 없다」인 경우다. 「리뷰 실패」와 한 칸에 넣으면 **고칠 수 없는 것에 Q-6 예산
3바퀴를 통째로 태운다.**

| 값 | 뜻 | 호출자(#21)가 |
|---|---|---|
| `PASS` | 문제를 못 찾았다 | 다음 단계로 |
| `CHANGES_REQUESTED` | 문제를 찾았다 | **재시도가 의미 있다** |
| 🔴 **`UNDETERMINED`** | **판정할 근거가 없다** | **재시도하지 않는다** — 같은 입력에 같은 결과다 |

Q-8 이 「못 읽음 ≠ 금지」로 가른 것과 **같은 축**이다.

⚠️ **전송 계층 실패는 여기 오지 않는다.** 절단·타임아웃·5xx 는 `LlmException` 으로
어댑터가 던진다(#10 의 「잘린 JSON 을 소비자가 파싱하게 두지 않는다」).
`UNDETERMINED` 는 **응답은 정상인데 판정이 없는** 경우다.

🔴 **호출자에게 남기는 규약** — 통과 판정을 `verdict != CHANGES_REQUESTED` 로 쓰지 않는다.
그 한 줄에서 `UNDETERMINED` 가 조용히 통과로 접힌다. **`verdict == PASS`** 로만 쓴다.

```java
public record DiffReview(
        ReviewVerdict verdict,          // PASS · CHANGES_REQUESTED · UNDETERMINED
        boolean satisfiesIssue,         // 이슈 요구 충족
        boolean withinScope,            // 범위 초과 아님
        Boolean followsConventions,     // 🔴 null = 판정 불가 (규약을 모른다) — §3.5
        boolean testsAdequate,          // 테스트 적절성
        @ExternalText(LLM_RESPONSE) String summary,   // compact 생성자가 redact
        List<String> findings)          // 각 항목도 redact
```

**compact 생성자가 강제하는 것**

| | |
|---|---|
| 🔴 `summary`·`findings` 전부 `TokenRedactor.redact` | S-4 · 등록표 `VALUE_TYPE` 의 근거 |
| `verdict` 가 `null` 이면 거부 | 「모르는 값을 DB 에 넣지 않는다」 — `IssueAnalysis` 선례 |
| 길이 상한 초과면 거부 | 절단하지 않는다 |
| `findings` 는 불변 복사 | 밖에서 바꿀 수 없다 |

⚠️ `IssueAnalysis` 가 **`category` 도 자유 문자열이라 같은 방어가 필요하다**는 것을 놓쳤다가
고친 전례가 있다(#11). 여기서는 **모델이 채우는 문자열 필드가 둘**(`summary`·`findings`)이고
둘 다 같은 조건이므로 **둘 다** 거친다.

### 3.3 `DiffReviewer` — 관찰값만 돌려준다

```java
public interface DiffReviewer {
    DiffReview review(AgentRunContext context, DiffReviewRequest request);
}
```

🔴 **`boolean` 이나 「통과/실패」를 돌려주지 않는다.** `IssueAnalyst` javadoc 이
「`REJECTED` 여부는 호출자가 임계로 정한다」로 못 박아 둔 것과 같은 이유다 —
**임계가 능력 안에 들어가면 그것을 바꾸려고 어댑터를 고치게 되고, 리뷰에 안 보인다.**

`AgentRunContext` 를 받는 이유 — `attempt` 가 거기 있고(Q-6), `RecordingLanguageModel` 이
그것으로 비용을 기록한다. 내가 기록 코드를 쓰지 않는 근거다(FR-6).

### 3.4 `DiffReviewRequest` — 입력 셋을 값으로 묶는다

```java
public record DiffReviewRequest(
        String diff,                      // 이미 스크럽 대상. 상한 초과는 거부(§2.2)
        AnalyzableIssue issue,            // 이슈 원문 — issue 도메인의 값 타입 (규율 ④)
        ContributionConstraints constraints)  // 저장소 규약 — repository 도메인의 값 타입
```

⚠️ **두 도메인의 값 타입을 import 한다.** 규율 ④ 가 막는 것은 **엔티티·Spring Data** 이고
값 타입은 허용된다 — `AnalyzableIssue`(#11)·`RepositoryCoordinates` 가 이미 그렇게 쓰인다.

### 3.5 「모른다」를 「위반 없음」으로 적지 않는다

`ContributionConstraints.unknown()` 이면 「저장소 관습 위반」 축을 판정할 근거가 없다.

🔴 이때 `followsConventions = true` 로 두면 **「확인 안 함」이 「문제 없음」으로 번역**된다 —
S-5 가 「파싱 실패는 허용이 아니라 보류」로 막는 그 오역과 같은 모양이다.

**확정** — `followsConventions` 를 **`Boolean`** 으로 둔다. `null` = 판정 불가다.
`RuleReading.aiContributionAllowed` 가 정확히 같은 이유로 `Boolean` 이고(「원시 `boolean` 으로
바꾸면 판정 실패가 `false` 로 뭉개진다」), 그 선례를 따른다.

⚠️ **`null` 이 곧 `CHANGES_REQUESTED` 는 아니다.** 규약을 모르는 것은 diff 의 흠이 아니다.
축 하나가 판정 불가라고 전체를 되돌리면 **정책 행이 없는 저장소가 영영 통과하지 못한다.**
전체 `verdict` 를 어떻게 접을지는 **호출자(#21)** 가 정한다 — 이 능력은 관찰값만 준다.

### 3.6 생성·수정 파일

**생성**

| 파일 | |
|---|---|
| `candidate/domain/DiffReviewer.java` | 능력 |
| `candidate/domain/DiffReview.java` | 값 타입 — 스크럽·스키마 강제 |
| `candidate/domain/DiffReviewRequest.java` | 입력 묶음 |
| `candidate/domain/ReviewVerdict.java` | `PASS` · `CHANGES_REQUESTED` · 🔴 **`UNDETERMINED`** — §3.2 |
| `candidate/domain/DiffReviewRejectedException.java` | 스키마·상한 위반. 🔴 **사유 enum 을 싣는다**(`TOO_LARGE`·`SCHEMA`) — 둘 다 **재시도 대상이 아니다**(§2.2) |
| `candidate/adapter/out/llm/LlmDiffReviewer.java` | 2층 어댑터 |
| 🔴 `candidate/**application**/DiffReviewProperties.java` | diff 상한 · 출력 토큰 상한. **`adapter/out/llm` 이 아니다** — 아래 |
| `config/DiffReviewConfig.java` | 빈 조립. `IssueAnalysisConfig` 선례 — 메서드 단위 `@Profile("!fakes")` |
| `candidate/domain/FakeDiffReviewer.java` (src/test) | 능력 대역 + `@FakeAdapter`. 🔴 **실패 모드를 재현**한다(깨진 응답·판정 불가) |

**수정**

| 파일 | |
|---|---|
| ~~`candidate/domain/GeneratedChange.java`~~ | 🔴 **건드리지 않는다** — §0.3 |
| `support/ExternalTextScrubRegistryTest.java` | `DiffReview.summary`·`findings` 행 신규(`VALUE_TYPE`) · `reviewResult` 는 사유만 정정 |
| `application.yml` · `.env.example` | 새 설정값이 있으면 |
| `.claude/rules/context/glossary.md` | `DiffReviewer`·`DiffReview` 용어 |

⚠️ **`DiffReviewProperties` 를 `adapter/out/llm` 에 두면 `ExternalAdapterIsolationTest` 가 잡는다** —
#11 에서 `IssueAnalysisProperties` 가 정확히 그랬고 `application` 으로 옮겼다.
🔴 **같은 실수를 반복하지 않는다.** 처음부터 `candidate/application` 에 둔다.

### 3.7 테스트

| # | 테스트 | 무엇을 막나 |
|---|---|---|
| 1 | `리뷰_원문은_스크럽을_거치지_않고는_만들_수_없다_S4()` | 🔴 값 타입 강제 |
| 2 | `String_을_그대로_받는_생성_경로가_없다_S4()` | 🔴 **#73 — 「가드가 있다 ≠ 이 입력에 닿는다」.** ⚠️ `reviewResult` 대입 경로는 아직 없으므로 **거기까지 주장하지 않는다** |
| 3 | `diff_가_상한을_넘으면_자르지_않고_거부한다()` | §2.2 |
| 4 | `판정이_없는_응답은_거부한다()` | 「모르는 값을 DB 에 넣지 않는다」 |
| 5 | `규약을_모르면_관습_축을_판정_불가로_둔다_S5()` | 🔴 「모른다」를 「문제 없음」으로 번역하지 않는다 |
| 5b | `판정_불가는_통과가_아니다_S5()` | 🔴 **`UNDETERMINED` 가 `PASS` 로 접히지 않는다.** #19 가 같은 이름의 테스트로 고정했다 |
| 5c | `판정_불가는_재시도_대상이_아니다()` | 같은 입력에 같은 결과다 — Q-6 예산을 태우지 않는다 |
| 6 | `리뷰는_상태를_전이시키지_않는다_S6()` | FR-5 — **통과가 제출이 아니다** |
| 7 | `임계를_능력이_갖지_않는다()` | NFR-2 — 관찰값만 |
| 8 | `LLM_호출은_AgentRun_에_기록된다_FR6()` | 데코레이터를 실제로 타는지. ⚠️ `fakes` 프로파일에서 `RecordingLanguageModel` 이 빠지므로 **#25 에서 쓴 수법**(운영 체인을 손으로 조립)이 필요하다 |
| 9 | `깨진_JSON_은_거부한다()` | 실패 모드 재현 (Q-9) |
| 10 | (기존) `PromptBoundaryTest` | ⚠️ `candidate/adapter/out/llm` 에 새 LLM 어댑터가 생긴다. **로그 인자에 본문성 값을 넣으면 잡힌다** — `LlmIssueAnalyst` 처럼 스칼라만 찍는다 |

**자기 규율** — 1·2·5 는 부러뜨려 적색을 확인한다. 특히 2 는 통과해도 공허해지기 쉽다.

---

## 4. 구현 순서

| 단계 | 내용 | 선행 |
|---|---|---|
| 1 | `ReviewVerdict` · `DiffReview` · `DiffReviewRequest` + 값 타입 테스트 | — |
| 2 | `DiffReviewer` 능력 + 페이크 | 1 |
| 3 | `LlmDiffReviewer` + 프롬프트 조립 + 응답 파싱 | 2 |
| 4 | 등록표 행 (신규 2 + 정정 1) | 1 |
| 5 | 통합 테스트 · 기록 검증 | 3·4 |
| 6 | 문서 — glossary | 5 |

**순차**다. 2·3 이 1의 타입을 쓰고 5가 전부를 쓴다.

---

## 5. 리스크

| # | 리스크 | 대응 |
|---|---|---|
| R-1 | ~~`GeneratedChange.java` 3-way 경합~~ | ✅ **없앴다** — #19 와 함께 기록 메서드를 #18 이후로 미뤘다(§0.3). 이 PR 은 그 파일을 건드리지 않는다 |
| R-2 | 🔴 **등록표 3-way 경합** — `Map.entry` 가 연속 줄이라 자동 병합이 안 될 수 있다 | 각자 자기 행만. 머지 직전 `origin/main` 당겨 받고 **`--rerun-tasks`** 로 그 테스트를 다시 돈다(#61) |
| R-3 | 호출자가 없어 **죽은 코드**가 된다 | #19 가 작업 중이고 호출 지점을 만든다. §0.1 |
| R-4 | 프롬프트 품질을 이 단계에서 검증할 수 없다 | 실제 LLM 을 타는 테스트를 만들지 않는다(Q-9). **조립과 파싱만** 검증하고 품질은 수동 |
| R-5 | ⚠️ 「몇 점이면 실패인가」가 비어 있다 | 의도다(NFR-2). #21 이 채운다 — **그 사실을 PR 본문에 적는다** |

---

## 6. 범위 밖 — 명시적으로 남긴다

🔴 **새 이슈를 만들지 않는다.** 아래는 PR 본문과 기존 이슈에 남긴다.

| | 왜 | 어디 |
|---|---|---|
| 리뷰 실패 → 코딩 단계 되돌림 | 재시도 루프의 일이다 | #21 |
| 파이프라인 호출 지점 | 검증 파이프라인이 소유한다 | #19 |
| `READY_FOR_PR` 전이 | S-6 — 전이는 이 능력의 일이 아니다 | #19 · #21 |
| 리뷰 임계 정의 | 관찰값과 판정을 가른다 | #21 |
| 🔴 **FR-4 — `review_result` 저장** | `GeneratedChange` 에 생성 팩토리가 없다(§0.3) | **#20 자신** — 아래 |

#### 🔴 FR-4 는 「미룬 것」이지 「없앤 것」이 아니다 — 그래서 이슈를 닫지 않는다

검토가 정확히 짚었다 — §0.3 가 「#18 이후」라고만 하고 **주인을 적지 않으면 완료 조건
하나가 미아가 된다.** #18 은 *팩토리* 소유자일 뿐 `recordReview` 를 만들 의무가 없다.

**처리** — 이 PR 이 머지돼도 **이슈 #20 을 닫지 않는다.** 남은 것과 선행(#18 의 팩토리)을
그 이슈에 코멘트로 남기고 열어 둔다.

⚠️ `/work` Phase 6 은 「머지되면 이슈를 닫는다」인데 **여기서는 따르지 않는다.**
닫으면 「리뷰 결과를 DB 에 저장한다」가 아무도 모르게 사라진다 —
`ExternalTextScrubRegistryTest` 의 `reviewResult` 행이 영원히 `PENDING` 으로 남는 것이
그 증거로 남지만, **증거는 할 일이 아니다.**
