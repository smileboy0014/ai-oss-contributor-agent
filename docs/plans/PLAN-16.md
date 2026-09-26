# PLAN-16: 구현 계획 수립 + 계획 검증

**이슈**: [#16](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/16)
**type**: feature
**작성일**: 2026-09-26
**작성자**: smileboy0014

---

## 0. 이 이슈가 무엇인가

PRD §13. **저장소 컨텍스트(#15) + 이슈 → 구현 계획**을 만들고, 코딩(#18)에 넘기기 전에 **검증**한다.

검증이 따로 있는 이유가 이 이슈의 본질이다 — LLM 계획을 그대로 실행하면 **엉뚱한 파일을
고치는 데 샌드박스 수 분**을 태운다. 이 제품의 품질 축은 「좋은 코드를 쓰는가」가 아니라
**「나쁜 결과를 걸러내는가」**(PRD §30)이고, 계획 검증이 그 게이트 중 가장 싼 것이다.
여기서 거르면 LLM 호출 한 번이고, 못 거르면 샌드박스 30분이다.

### 파이프라인에서의 자리

```
SELECTED (attempt = 0)
   │  사람의 implement 호출
   ▼
  #15 RepositoryContext ──▶ ★#16 계획 수립 ──▶ 검증        ← 🔴 여기까지 attempt = 0
                                  ▲              │ 실패
                                  └──────────────┘ agent.plan.max-attempts 내 재생성
                                                 │
                        ┌────────────────────────┴───────────┐
                   통과 │                                    │ 상한 소진
                        ▼                                    ▼
              startImplementing()                        FAILED (종단)
              IMPLEMENTING (attempt = 1)                 🔴 새 전이 — §4 D-4
                        ▼
              #18 코딩 (CODE→VERIFY→REVIEW 루프)
```

🔴 **`PLANNING` 상태를 만들지 않는다.** 상태를 늘리면 S-6 의 전이 표가 흔들린다.

### 🔴 계획은 `IMPLEMENTING` 전이 **앞**이다 (검토 중대 지적 반영)

초안은 「계획은 `SELECTED → IMPLEMENTING` 전이 **안에서** 일어난다」고 적었다. **틀렸다.**
기존 코드가 반대를 못 박아 뒀다.

```java
// ContributionCandidate.java:114-117
// ⚠ AgentRun#getAttempt() 와 CODE·VERIFY·REVIEW 행에 한해 같은 값이다.
//   ANALYZE·PLAN 행의 attempt 는 항상 1 인데 그 시점 이 필드는 0 이다 —
//   둘을 무조건 같다고 보면 비용 집계가 어긋난다.
```

`startImplementing` 이 `this.attempt = 1` 을 세팅한다(`:278`). 전이 뒤에 계획하면
**`PLAN` 시점의 `candidate.attempt` 가 0 이 아니라 1** 이 되어 위 불변식이 깨지고,
주석이 경고한 「비용 집계가 어긋난다」가 그대로 발생한다.

**그래서 순서를 뒤집었다** — 계획이 통과해야 `startImplementing` 을 부른다.
부수 효과가 하나 더 좋다: **계획도 못 세운 후보가 `IMPLEMENTING` 에 들어가지 않는다.**

---

## 1. 요구사항

### 기능 요구사항 (FR)

| # | 요구사항 | 근거 | 증거 |
|---|---------|------|---|
| FR-1 | **계획 생성** — 수정할 파일 · 변경 내용 · 테스트 전략 | 이슈 완료조건 1 | Stage 3 |
| FR-2 | **지목한 파일이 실재하는가** — 🔴 기준은 **「컨텍스트가 보여준 파일」**이다 (§4 D-5) | 이슈 완료조건 2 | Stage 2 유닛 |
| FR-3 | **저장소 규약을 지키는가** — `tests_required` 인데 테스트 전략이 없으면 거부 | 이슈 완료조건 2·4 · S-5 | Stage 2 유닛 |
| FR-4 | **범위가 이슈를 넘지 않는가** — 🔴 **두 눈금**이다: 고정 상한(설정) + **이슈별 추정치**(`IssueAnalysis`) (§4 D-6) | 이슈 완료조건 2 | Stage 2 유닛 |
| FR-5 | **검증 실패 시 재생성** — 상한 내. 🔴 **새 축이다** (D-1) | 이슈 완료조건 3 | Stage 4 |
| FR-6 | `RepositoryPolicy` 의 제약을 **프롬프트에 싣는다** | 이슈 완료조건 4 · S-5 | Stage 3 |
| FR-7 | `AgentRun` 기록 — stage `PLAN` · 토큰 | 이슈 완료조건 5 | `RecordingLanguageModel` 이 강제 |

### 비기능 요구사항 (NFR)

| # | 항목 | 기준 |
|---|------|------|
| NFR-1 | 비용 | 후보 1건당 **LLM 호출 최대 2회**(`agent.plan.max-attempts`). 전송 재시도와 곱하면 **2 × (1+2) = 6회**.<br>여기에 #15 의 GitHub 호출(최대 26 논리 호출)이 앞에 붙는다 |
| NFR-2 | 프롬프트 크기 | `RepositoryContext` 가 이미 `agent.context.max-total-chars`(120,000)로 상한이 서 있다. **여기서 다시 늘리지 않는다** |
| NFR-3 | 결정성 | 검증은 **순수 함수**다. 같은 (계획, 컨텍스트, 규약)이면 같은 판정이 나온다 |
| NFR-4 | 트랜잭션 | UseCase 에 `@Transactional` 을 달지 않는다. LLM 호출이 들어 있다 |

---

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키는가 |
|---|---|---|
| S-1 원본 저장소 쓰기 금지 | — | 이 PR 은 **아무것도 쓰지 않는다.** 대상 저장소 호출은 #15 의 읽기뿐이다 |
| S-2 항상 draft · 자동 머지 금지 | — | PR·리뷰·코멘트 API 를 호출하지 않는다 |
| S-3 샌드박스 밖 실행 금지 | — | 계획은 **텍스트**다. 실행하지 않는다. `SandboxCommand` 를 건드리지 않는다.<br>⚠️ 오히려 이 이슈의 존재 이유가 **샌드박스 시간을 아끼는 것**이다 |
| S-4 시크릿 유출 금지 | 🔴 ✅ | ① 프롬프트에 대상 저장소 파일이 실린다 — `PromptScrubber` 가 송신 직전 강제(어댑터 계약). ② `ImplementationPlan` 의 자유 텍스트는 **값 타입이 스크럽을 강제**한다 (`IssueAnalysis` 와 같은 수법) |
| S-5 대상 저장소 규약 우선 | 🔴 ✅ | ① 게이트 — #15 의 `BuildRepositoryContextUseCase` 가 이미 `assertContributionAllowed` 를 건다. **이 UseCase 는 그것을 타고 들어가므로 중복으로 걸지 않는다** (근거는 §4 D-3). ② 규약 제약을 **프롬프트에 싣고**(FR-6) **검증에서 다시 본다**(FR-3) — 모델이 지켰다고 믿지 않는다 |
| S-6 승인 지점 우회 금지 | 🔴 ✅ | ① **엔드포인트를 만들지 않는다.** 이 UseCase 는 `implement` 게이트(#18·#21이 만든다) 뒤에서 불린다. 스케줄러·이벤트 진입점 없음. ② `selectedAt` 검사는 `startImplementing` 이 이미 한다 — 우회하지 않는다. ③ 🔴 **상태 전이를 하나 연다** — `SELECTED → FAILED` (§4 D-4). **상한 소진이 `FAILED` 로 가지 못하면 후보가 박힌다** |

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리는가 | 처리 |
|---|---|---|
| Q-1 GitHub 연동 | — | GitHub 을 직접 부르지 않는다. #15 의 UseCase 를 탄다 |
| Q-2 마이그레이션 | — | **없다** (D-2) |
| Q-3 실행 프로필 | — | 진입점을 만들지 않는다 |
| Q-4 샌드박스 네트워크 | — | 샌드박스를 쓰지 않는다 |
| **Q-6 재시도 상한** | 🔴 ✅ | **정면으로 걸린다** — §4 D-1. 사용자 확정으로 **3번째 축을 신설**했다 |
| Q-7 Lombok | — | 엔티티를 추가·변경하지 않는다. 신규 타입은 전부 `record` |
| Q-8 AI 기여 금지 판정 | ✅ | #15 의 게이트를 그대로 탄다 (S-5 ①) |
| Q-9 테스트 대역 | ✅ | 능력 소비자 층 — `FakeLanguageModel`(기존) + 신규 `FakeImplementationPlanner`. **어댑터 매핑·전송 계약 층은 추가하지 않는다** — 새 HTTP 표면이 없다 |
| Q-10 CI | — | 구성을 바꾸지 않는다 |
| Q-11 SDK vs 직접 구현 | — | 새 대외 의존 없음 |

**가정** — 틀리면 어디를 고치는가.

1. **`implement` 엔드포인트는 이 PR 이 만들지 않는다.** 지금 `CandidateController` 에는 GET 2개뿐이고,
   PRD §23 의 `implement` 게이트는 파이프라인을 조율하는 이슈(#18·#21)의 몫으로 본다.
   틀리면 이 UseCase 를 부르는 얇은 컨트롤러 하나가 추가된다 — 이 PR 의 계약은 그대로다.
2. **계획 규모 상한(파일 8 · 변경 400줄)이 Phase 1 대상에 맞다.** 실측 0건의 추정이라 설정으로 뺐다.

---

## 3. 스코프

| 도메인 | 변경 | 소유 판정 근거 |
|---|---|---|
| `candidate` | **신규** | 계획은 **후보에 속한 산출물**이다. `IssueAnalyst`(능력) / `LlmIssueAnalyst`(구현) 와 같은 배치 |
| `repository` | **신규 값 + UseCase 메서드 1개** | 규약 제약을 `candidate` 에 넘기려면 **값 타입**이 필요하다 — 아래 |
| `agent` | — | `LlmCallSite.PLAN` 이 **이미 있다.** 새 호출 지점을 만들지 않는다 |
| `issue` | 🔴 **UseCase 메서드 1개 추가** | 초안은 「import 만 한다」로 판정했는데 **틀렸다** — 후보의 `issueId` 로 `AnalyzableIssue` **단건**을 얻는 경로가 없다. `FindAnalyzableIssuesUseCase` 는 **페이지 조회 하나뿐**이다 |

### 🔴 계약 표면 변경 2건

**① `repository` 가 규약 제약을 값으로 내보낸다**

```java
// com.ossagent.repository.domain.ContributionConstraints  (신규 값)
public record ContributionConstraints(
        String javaVersion, String buildCommand, String testCommand,
        boolean testsRequired, boolean issueReferenceRequired, boolean signoffRequired) { }

// com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase  (메서드 추가)
ContributionConstraints constraintsOf(Long repositoryId);
```

🔴 **`candidate` 가 `RepositoryPolicy` 를 직접 import 하면 규율 ④ 위반이다** — 그것은
`repository` 애그리거트의 **멤버 엔티티**다. 넘어가는 것은 값 하나여야 한다.
`AnalyzableIssue`(issue → candidate)와 같은 패턴이다.

**② `candidate` 가 계획 수립 능력을 선언한다**

```java
// com.ossagent.candidate.domain.ImplementationPlanner  (능력 — 2층)
ImplementationPlan plan(Long candidateId, PlanningInput input, int attempt);
```

구현은 `candidate/adapter/out/llm/LlmImplementationPlanner` — `LanguageModel` 위에 얹히는
**2층**이고, `IssueAnalyst` 와 같은 구조다.

---

## 4. 기술 설계

### 결정 3건

#### D-1. 🔴 계획 재생성은 **3번째 축**이다 (사용자 확정 · 2026-09-26)

이슈 완료조건 「검증 실패 시 재생성 (상한 내)」과 Q-6 의 「`ANALYZE`·`PLAN` 은 파이프라인
재시도 없음. 실패는 즉시 `FAILED`」가 **정면으로 읽히는 자리**다.

**가르는 것은 실패의 종류다.**

| 축 | 설정 키 | 무엇을 세나 | 이 PR |
|---|---|---|---|
| 전송 계층 | `agent.llm.max-retries` (2) | 429·5xx·타임아웃 | 그대로 |
| 파이프라인 | `agent.execution.max-retries` (3) | `CODE`→`VERIFY`→`REVIEW` **한 바퀴** | **건드리지 않는다** |
| **계획 검증** 🆕 | `agent.plan.max-attempts` (2) | 「LLM 이 **없는 파일을 지목**했다」 | **신설** |

Q-6 이 말한 「PLAN 은 재시도 없음」은 **루프 카운터** 이야기다. 전송 실패도 아니고 코드
실패도 아닌 **의미 실패**(모델이 스키마는 맞췄는데 내용이 틀렸다)를 다루는 축이 없었다.

**기각한 두 안**

- **즉시 `FAILED`** — 파일 경로 하나를 틀렸다고 후보가 **종단**으로 떨어진다. `REJECTED`·`FAILED`
  는 되돌릴 수 없고 재분석 경로가 없다. 이슈의 완료조건도 버려진다
- **`agent.execution.max-retries` 재사용** — PLAN 에서 2번 태우면 정작 고쳐야 할 `CODE` 루프에
  1회만 남는다. Q-6 이 「후보 전체 통합」을 기각한 이유가 정확히 이것이다

⚠️ **`AgentRun.attempt` 는 PLAN 에서 항상 1 이다** (Q-6). 재생성하면 **`attempt=1` 인 PLAN 행이
여러 개** 쌓인다.

🔴 **초안은 이것의 근거로 Q-6 의 「같은 사이클의 3행이 같은 값을 갖는다」를 들었는데 오독이다.**
그 3행은 `CODE`·`VERIFY`·`REVIEW` 라는 **서로 다른 stage** 를 뜻하지, 같은 stage 가
중복되는 것을 인정한 문장이 아니다.

**제대로 된 근거는 이것이다** — `AgentRun` 은 append-only 이고 `(candidate_id, stage, attempt)`
에 UNIQUE 제약이 없다(`V2` 는 INDEX 다). 그리고 **그 중복 자체가 관측값이다**:

```sql
-- 이 후보의 계획을 몇 번 다시 세웠나
SELECT count(*) FROM agent_run WHERE candidate_id = ? AND stage = 'PLAN'
```

호출 1회 = 행 1개이므로 **행 수가 곧 시도 횟수**다. 새 축의 비용이 DB 에서 보인다 —
「비용이 보이지 않으면 재시도 루프가 조용히 돈을 태운다」(`external-deps.md`)에 걸리지 않는다.
⚠️ 다만 **`attempt` 컬럼으로는 구분되지 않는다.** 회차를 알고 싶으면 `started_at` 순서를 본다.

⚠️ **곱셈 예산** — PLAN 이 2회, 각각 전송 재시도 2회까지 → **LLM 호출 최대 6회**.
`agent.plan.max-attempts` 를 올릴 때 이 곱을 먼저 계산한다.

#### D-4. 🔴 `SELECTED → FAILED` 전이를 연다 (검토 중대 지적 반영)

**S-6 이 요구하는데 길이 없었다.** 「상한 소진은 `FAILED` 이고, 그 자체가 사람에게 넘기는
신호」인데, 계획이 `IMPLEMENTING` **앞**에서 일어나므로(위 §0) 소진 시점의 상태는 `SELECTED` 다.
그런데 `SELECTED.allowedNext = {IMPLEMENTING, REJECTED}` 라 **`FAILED` 로 갈 수 없다.**

그대로 두면 후보가 `SELECTED` 에 박히고 **사람이 볼 신호가 발생하지 않는다.**

**선례가 정확히 있다** — `ANALYZING → FAILED` 가 같은 이유로 열려 있다.

```java
// CandidateStatus.java:23-25
// 분석 실패는 즉시 FAILED 다 — Q-6 확정. 이 전이가 없으면 ANALYZING 에서 나가는 길이
// ANALYZED 하나뿐이라, LLM 분석이 실패한 후보가 영구히 박힌다.
// 재분석도 FAILED 도 불가능해 「사람에게 넘기는 신호」가 발생하지 않는다 (S-6)
```

| 확인 | 답 |
|---|---|
| 종단에서 나가는 전이인가 | ❌ `SELECTED` 는 종단이 아니다 (Q-5 가 `SELECTED → REJECTED` 를 이미 열었다) |
| 승인 지점을 우회하나 | ❌ `selectedAt` 은 이미 찍혀 있다. **사람이 고른 뒤** 시스템이 실패한 것이다 |
| 자동 취소 경로인가 | ❌ `REJECTED` 가 아니라 `FAILED` 다. 「사람이 안 고른 것」이 아니라 「기계가 못 한 것」 |

⚠️ **`REJECTED` 로 가지 않는 것이 중요하다.** `REJECTED` 는 Q-5 가 **사람 행위로만** 일어나게
못 박은 상태다. 기계가 그리로 보내면 「선택 취소는 사람만」이 무너진다.

#### D-5. 실재 검사의 기준은 **「컨텍스트가 보여준 파일」**이다 (검토 중대 지적 반영)

초안은 「컨텍스트·**트리**에 없는 경로를 거부」라고 적었는데, **`RepositoryContext` 는 트리를
들고 있지 않다** — 필드는 선별된 `files`(최대 12개)와 `treeSha` 문자열뿐이다.
D-3 이 「컨텍스트를 직접 만든다」로 계약을 고정했으므로 검증기가 트리에 닿을 수단도 없다.

**그래서 기준을 좁힌다 — 그리고 그것이 옳다.** 모델에게 준 것이 그 파일 목록이므로,
**보여주지 않은 파일을 고치겠다는 계획은 근거가 없다.** `agent.plan.max-planned-files`(8)를
`agent.context.max-files`(12)보다 작게 잡은 이유와 같은 논리다.

| 상황 | 판정 |
|---|---|
| `MODIFY` 인데 컨텍스트에 없다 | ⛔ 거부 — 「본 것만 고친다」 |
| `CREATE` 인데 컨텍스트에 없다 | ✅ 정상 — 새 파일은 없는 것이 당연하다 |
| 🔴 `CREATE` 인데 컨텍스트에 **있다** | ⛔ 거부 — #18 이 그대로 실행하면 **남의 파일을 덮어쓴다** |

⚠️ **컨텍스트가 잘렸을 때**(`RepositoryContext.isPartial()`)는 실재하는 파일을 「없다」고
판정할 수 있다. 무고한 거부다. 그래서 **거부 사유에 그 사실을 함께 적는다** — 재생성
프롬프트가 「네가 본 목록 밖이다」를 알면 모델이 목록 안에서 다시 고른다.
R-1(검증이 지나치게 빡빡함)의 가장 구체적인 발현이 여기다.

#### D-6. 범위 검사는 **두 눈금**이다 (검토 지적 반영)

초안은 고정 상한(파일 8 · 400줄)만 뒀는데, 그러면 **파일 7개짜리 무관한 계획이 그대로
통과**한다. 이슈 본문이 물은 것은 「범위가 **이슈를** 넘지 않는가」다.

이슈별 눈금은 **이미 후보에 영속돼 있다** — `IssueAnalysis.estimatedFiles`·`estimatedLoc`
(#11 이 적재한 `contribution_candidate` 컬럼)이다. 그것을 쓰지 않을 이유가 없다.

| 눈금 | 출처 | 막는 것 |
|---|---|---|
| **고정 상한** | `agent.plan.max-planned-*` | 파이프라인 폭주 — 절대 천장 |
| **이슈별 허용** | `분석 추정치 × agent.plan.scope-tolerance`(2.0) | **scope creep** — 「이 이슈가 그만한 일이 아니다」 |

⚠️ 추정치가 `0`·`null` 이면 이슈별 검사를 **건너뛴다.** 분석이 추정을 못 낸 것을
「0줄만 고쳐야 한다」로 읽으면 모든 계획이 거부된다 — 모르는 것을 최강 제약으로
번역하지 않는다(Q-6 의 `maxAttempts = 0` 함정과 같은 계열이다).

#### D-2. 계획을 **영속화하지 않는다** (사용자 확정)

`PLAN → CODE` 가 **`implement` 호출 하나 안에서** 끝난다. API 호출 사이를 넘을 필요가 없으므로
컬럼이 없어도 성립한다. #15 의 D-2 와 같은 결이다.

🔴 **덧붙여 지금 마이그레이션을 만들면 위험하다.** V8 을 #24 세션이 선점했고,
**내 V9 가 먼저 머지되면 뒤에 오는 V8 을 Flyway 가 거부한다** — 이미 적용된 버전보다 낮은
번호는 `outOfOrder`(기본 `false`)가 막는다. H2 는 매번 새로 떠서 드러나지 않고
**PostgreSQL 개발 DB 에서만** 터진다.

⚠️ 초안은 이것을 `baseline-on-migrate` 탓으로 적었는데 **틀렸다.** 그 설정은 「이미 있는
스키마를 인정할 것인가」이고 순서와 무관하다. 근거가 틀린 문장은 다음 사람이 그대로 인용한다.

**관측은 로그로** — 계획이 지목한 **파일 목록과 개수**를 `INFO` 로 남긴다(내용은 아니다).
영속화가 필요해지는 시점은 #18 이 `generated_change` 를 쓸 때이고, 그때 함께 정한다.

#### D-3. S-5 게이트를 **중복으로 걸지 않는다**

`BuildRepositoryContextUseCase`(#15)가 진입부에서 `assertContributionAllowed` 를 건다.
이 UseCase 는 컨텍스트를 **그것을 통해서만** 얻으므로 게이트를 이미 지난다.

⚠️ 그런데 이 판단에는 **전제가 있다** — 「컨텍스트는 항상 #15 를 통해 온다」.
호출자가 `RepositoryContext` 를 **밖에서 만들어 넣으면** 게이트를 건너뛴다.
그래서 이 UseCase 는 `RepositoryContext` 를 **파라미터로 받지 않고 직접 만든다.**
계약으로 막는 쪽을 택한다 — 「부르는 사람이 기억한다」는 언젠가 빠진다(S-4 의 교훈과 같다).

### 변경 파일

| # | 경로 | 레이어 | 신규/수정 | 내용 |
|---|------|--------|----------|------|
| 0a | `candidate/domain/CandidateStatus.java` | domain | **수정** | 🔴 `SELECTED → FAILED` 전이 추가 (D-4) |
| 0b | `candidate/domain/ContributionCandidate.java` | domain | **수정** | 🔴 `failPlanning(Clock)` — `SELECTED` 에서 부르는 실패. `fail` 과 가르는 이유는 호출 조건이 다르기 때문이다 |
| 0c | `issue/application/FindAnalyzableIssuesUseCase.java` | application | **수정** | 🔴 `findOne(issueId)` — 후보 1건의 이슈를 값으로. 계약 표면 |
| 0d | `candidate/domain/PlanningInput.java` · `ScopeLimits.java` | domain | 신규 | 입력 묶음 · 범위 눈금 2종 (D-6) |
| 1 | `repository/domain/ContributionConstraints.java` | domain | 신규 | 규약 제약 값 — 🔴 계약 표면.<br>⚠️ **`PolicyClearance`(#24)와 합치지 않는다** — 저쪽은 「해도 된다」(권한), 이쪽은 「이렇게 해라」(데이터). 합치면 빌드 명령을 알고 싶어 부른 호출이 **통행증을 부산물로** 쥐여 준다 |
| 2 | `repository/application/AnalyzeRepositoryPolicyUseCase.java` | application | **수정** | `constraintsOf(repositoryId)` 추가 |
| 3 | `candidate/domain/ImplementationPlanner.java` | domain | 신규 | 능력 (2층) — 🔴 계약 표면 |
| 4 | `candidate/domain/ImplementationPlan.java` | domain | 신규 | 산출물 값. 🔴 스크럽 강제 + 스키마 강제 |
| 5 | `candidate/domain/PlannedFile.java` | domain | 신규 | 파일 1건 — 경로 · 변경 의도 · 신규 여부 |
| 6 | `candidate/domain/PlanRejectedException.java` | domain | 신규 | 스키마 위반 (`AnalysisRejectedException` 과 같은 결) |
| 7 | `candidate/domain/PlanValidator.java` | domain | 신규 | **순수** — 실재 · 규약 · 범위 3검사 |
| 8 | `candidate/domain/PlanVerdict.java` | domain | 신규 | 검증 결과 — 통과 여부 + **위반 사유 목록**(재생성 프롬프트에 되먹인다) |
| 9 | `candidate/application/PlanImplementationUseCase.java` | application | 신규 | 조율 — 컨텍스트 → 계획 → 검증 → 재생성 |
| 10 | `candidate/application/ImplementationPlanProperties.java` | application | 신규 | `agent.plan.*` |
| 11 | `candidate/adapter/out/llm/LlmImplementationPlanner.java` | adapter/out | 신규 | 프롬프트 조립 · JSON 파싱 |
| 12 | `config/ImplementationPlanConfig.java` | config | 신규 | 빈 조립 + 프로퍼티 바인딩 |
| 13 | `src/main/resources/application.yml` | 설정 | 수정 | `agent.plan.*` |
| 14 | `src/test/.../candidate/domain/FakeImplementationPlanner.java` | test | 신규 | 대역 — **실패 모드 재현** |
| 15 | `src/test/.../support/ExternalTextScrubRegistryTest.java` | test | **수정 여부는 구현 때 판정** | `@ExternalText` 를 다는 필드가 생기면 등록한다 |

### 체크리스트 답변

| # | 항목 | 답 |
|---|------|---|
| 1 | 소유 도메인 | `candidate` (계획은 후보의 산출물) + `repository`(규약 값 제공) |
| 2 | 레이어 배치 | 검증은 **순수 → domain**, 조율은 **application**, 프롬프트·파싱은 **adapter/out** |
| 3 | 능력 인터페이스 | `ImplementationPlanner`(능력 이름) / `LlmImplementationPlanner`(기술 이름) |
| 4 | 🔴 트랜잭션 안 대외 호출 없음 | ✅ `@Transactional` 없음. DB 를 읽고 쓰지 않는다(D-2). `constraintsOf` 는 `repository` 쪽의 짧은 읽기 트랜잭션이고 **LLM 호출 전에** 끝난다 |
| 5 | 상태 전이 영향 | 🔴 **있다** — `SELECTED → FAILED` 신설 (D-4). `PLANNING` 상태는 만들지 않는다. `startImplementing` 은 **계획 통과 후**에만 부른다 |
| 6 | 멱등성 | 계획 수립 자체는 부작용이 없다(읽기 + LLM). 같은 입력에 **검증 판정**은 같다(NFR-3). 계획 자체는 LLM 이라 비결정적 — 그것을 거르는 것이 검증이다.<br>⚠️ 상한 소진 시의 `FAILED` 는 **종단**이라 재호출해도 전이가 거부된다 — 이중 적용이 불가능하다 |
| 7 | `Clock` 주입 | 🔴 **필요하다.** 초안은 「해당 없음」이라고 적었는데 틀렸다 — `failPlanning(clock)` 이 전이 시각을 찍는다 |
| 8 | 🔴 안전 경계 | §2 — S-4 · S-5 · S-6 접촉 |

### 데이터 모델

**해당 없음 — 마이그레이션 없음** (D-2). ⚠️ V8 은 #24 세션이 쓴다.

### API 계약

**해당 없음** — 엔드포인트를 만들지 않는다 (S-6 · 가정 1).

### 설정 (`agent.plan.*`)

| 키 | 기본 | 근거 |
|---|---|---|
| `max-attempts` | 2 | 🔴 D-1 의 3번째 축. 곱셈 예산 2 × (1+2) = 6 |
| `max-output-tokens` | 4000 | 계획은 분석(1,500)보다 길고 코드(16,000)보다 짧다 |
| `max-planned-files` | 8 | FR-4. `agent.context.max-files`(12)보다 작아야 한다 — 본 것보다 많이 고칠 수 없다 |
| `max-planned-loc` | 400 | FR-4. PR 컨벤션의 「< 400줄」과 같은 눈금 |

---

## 5. 구현 순서

### 실행 모드: **sequential**

**판정 근거** — Stage 1 의 값(`ImplementationPlan`)을 Stage 2~5 가 전부 import 한다.
유형은 `contract-and-impl` 이다.

| Stage | 내용 | 선행 | 파일 |
|-------|------|------|------|
| 1 | 값 계약 — `PlannedFile`·`ImplementationPlan`·`PlanRejectedException` + `ContributionConstraints` | 없음 | 1·4·5·6 |
| 2 | 검증 — `PlanValidator`·`PlanVerdict` (순수) | 1 | 7·8 |
| 3 | 능력 + 어댑터 — `ImplementationPlanner`·`LlmImplementationPlanner` | 1 | 3·11 |
| 4 | 조율 — `PlanImplementationUseCase` + 설정 + `constraintsOf` | 1~3 | 2·9·10·12·13 |
| 5 | 대역·테스트 전량 | 1~4 | 14·15 + 테스트 |

---

## 6. 테스트 계획

| # | 레벨 | 대상 | 검증 내용 |
|---|------|------|----------|
| 1 | 유닛 | `ImplementationPlan` | 스키마 강제 — 파일 0건·빈 경로·모르는 변경 종류를 거부 · 자유 텍스트가 **스크럽을 거친다** |
| 2 | 🔴 유닛 | `PlanValidator` FR-2 | **컨텍스트에 없는 경로를 지목하면 거부**한다 |
| 3 | 🔴 유닛 | `PlanValidator` FR-3 | `testsRequired` 인데 테스트 전략이 없으면 **거부**한다 (S-5) |
| 4 | 유닛 | `PlanValidator` FR-4 | 파일 수·LOC 상한 초과를 거부한다 |
| 5 | 유닛 | `PlanVerdict` | **위반 사유가 값으로 나온다** — 재생성 프롬프트에 되먹일 수 있어야 한다 |
| 6 | 🔴 유닛 | UseCase FR-5 | 1차 검증 실패 → **재생성 1회** → 통과. LLM 호출이 **정확히 2회** |
| 7 | 🔴 유닛 | UseCase 상한 | 상한 소진 시 ① 3회째 LLM 호출이 **없다** ② 후보가 **`FAILED`** 가 된다 (S-6 · D-4) ③ `startImplementing` 이 **불리지 않는다** |
| 7b | 🔴 유닛 | 전이 표 | `SELECTED → FAILED` 가 열렸고, **`SELECTED → REJECTED` 는 여전히 사람 행위 전용**이다 (Q-5) |
| 7c | 🔴 유닛 | FR-7 비용 관측 | 재생성이 1회 일어나면 **LLM 호출 2회**가 전부 기록된다 — 실패로 끝난 호출도 포함(`external-deps.md` 「실패도 기록한다」) |
| 7d | 유닛 | D-6 | 분석 추정치를 넘는 계획을 거부한다 · **추정치가 0이면 그 검사를 건너뛴다** |
| 7e | 유닛 | D-5 | `CREATE` 는 실재 검사에서 빠지고, **이미 있는 경로를 `CREATE` 로 지목하면 거부**된다 |
| 8 | 유닛 | UseCase D-3 | `RepositoryContext` 를 **파라미터로 받지 않는다** — 컨텍스트가 #15 를 통해서만 온다 |
| 9 | 유닛 | 규약 반영 FR-6 | `ContributionConstraints` 가 프롬프트에 실린다 |
| 10 | 유닛 | `ContributionConstraints` | `repository` 가 `RepositoryPolicy` 를 **값으로 내보낸다** — candidate 가 엔티티를 import 하지 않는다 |

### 🔴 가드는 「있다」가 아니라 「문다」

안전 경계에 닿는 검사 2개(테스트 2·3)에 **돌연변이 검증**을 돌리고
**「무엇을 빼니 몇 건이 빨개졌다」를 PR 본문에 숫자로** 적는다.

1. `PlanValidator` 에서 **파일 실재 검사**를 제거한다 → 빨개져야 한다
2. `PlanValidator` 에서 **`testsRequired` 검사**를 제거한다 → 빨개져야 한다
3. 되돌린다

**샘플의 대표성** — 테스트 2의 계획은 **컨텍스트에 실제로 없는** 경로를 지목해야 하고,
테스트 3의 규약은 **`testsRequired = true`** 여야 한다. 아니면 물림 단언이 공허하다.

---

## 7. 리스크

| # | 리스크 | 영향 | 대응 |
|---|--------|------|------|
| R-1 | **검증이 지나치게 빡빡해 정상 계획을 거부**한다 | 후보가 상한 소진으로 실패 | 검사 3개를 전부 **설정 가능한 상한**이나 **사실 대조**(파일 실재)로 둔다. 「모델이 맘에 안 든다」식 판정을 넣지 않는다 |
| R-2 | 재생성 프롬프트가 위반 사유를 못 싣는다 | 같은 실패가 반복돼 예산만 태운다 | `PlanVerdict` 가 **사유를 값으로** 들고, 어댑터가 그것을 프롬프트에 되먹인다(테스트 5) |
| R-3 | `implement` 엔드포인트가 없어 **아직 아무도 부르지 않는다** | 통합 검증이 미뤄진다 | 가정 1에 명시. #18·#21 이 배선한다 |
| R-4 | 계획 규모 상한이 실측 0건의 추정 | 정상 계획이 걸리거나 과대 계획이 통과 | 설정으로 뺐다 |
| R-5 | `constraintsOf` 추가가 `AnalyzeRepositoryPolicyUseCase` 를 건드린다 — **#24 세션이 같은 파일을 수정 중** | 머지 충돌 | 메서드 **추가**뿐이라 해소가 기계적이다. 착수 전 해당 세션에 알린다 |

**대외 호출 실패 시나리오**

| 상황 | 동작 |
|---|---|
| LLM 타임아웃·5xx | `agent.llm.max-retries` 가 흡수. **계획 축을 태우지 않는다** |
| LLM 절단(`TRUNCATED`) | `LlmPermanentException` — 전송 재시도 대상이 아니다. 계획 축에서 **1회로 센다**(출력이 예산을 넘은 것이고 다시 시도할 가치가 있다) |
| 응답이 스키마 위반 | `PlanRejectedException` → **계획 축에서 재생성 1회로 센다** |
| GitHub 레이트리밋 (#15 경유) | **전파** — 지연이다. 이 UseCase 가 삼키지 않는다 |
| 규약 보류·금지 | #15 게이트가 `ContributionNotAllowedException` 으로 막는다 |

---

## 8. 복잡도

| Stage | 파일 수 | 복잡도 |
|-------|--------|--------|
| 1 값 계약 | 4 | 낮음 |
| 2 검증 | 2 | **중간** — 이 PR 의 본체이자 가치 |
| 3 능력·어댑터 | 2 | **중간** — 프롬프트 + 되먹임 |
| 4 조율 | 5 | 중간 — 재생성 루프 |
| 5 대역·테스트 | 2 + 테스트 | 중간 |

---

## 9. 이 PR 이 **하지 않는** 것

- **`implement` 엔드포인트** — S-6 의 게이트다. #18·#21 이 만든다 (가정 1)
- **코드 생성** — #18
- **영속화** — D-2. 컬럼도 테이블도 없다. ⚠️ V8 은 #24 세션이 쓴다
- **`PLANNING` 상태 신설** — 전이 표를 건드리지 않는다
- **`agent.execution.max-retries` 변경** — D-1 에서 별도 축으로 갈랐다
