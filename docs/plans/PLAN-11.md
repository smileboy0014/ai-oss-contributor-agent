# PLAN-11: 이슈 분석 → ContributionCandidate 생성

- 이슈: [#11](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/11)
- 브랜치: `feature/11_issue-analysis-candidate`
- 근거: PRD §11 · `.claude/codemaps/domain.md`
- 선행: #5 · #9 · #10 · #12 — **전부 머지됨**

---

## 1. 요구사항

### 배경

필터(#9)가 이슈를 `PASSED`·`UNDECIDED`·`REJECTED` 로 가른다. 앞 둘이 분석 대상인데,
그 이슈를 **LLM 으로 분석해 기여 후보(`ContributionCandidate`)로 만드는 단계**가 비어 있다.

이 구멍이 만드는 것은 기능 부재만이 아니다.

| 막혀 있는 것 | 근거 |
|---|---|
| **#14** 스캔 트리거 연결 | 선행이 `#8, #9, #11` 인데 #11 만 비어 있다 |
| **#13** 조회 API | 머지됐지만 **테이블이 영원히 비어 있다** — 후보를 만드는 코드가 없다 |
| **#24** 선택 게이트 | `ANALYZED` 가 없으면 `selectByHuman` 을 부를 대상이 없다 |

#12 가 상태머신을, #10 이 `LanguageModel` 을, #7 이 S-5 게이트(`assertContributionAllowed`)를
각각 깔아 뒀다. **이 이슈는 그 셋을 잇는다.**

### 기능 요구사항 (FR)

| # | 요구 | 출처 |
|---|---|---|
| FR-1 | `PASSED`·**`UNDECIDED`** 이고 **아직 후보가 없는** 이슈를 **우선순위 순으로** 배치 분석한다 | 이슈 완료조건 · #9 인계 |
| FR-2 | 분석 프롬프트 + **응답 스키마 검증** — 파싱 실패를 성공으로 처리하지 않는다 | 이슈 완료조건 |
| FR-3 | `contribution_candidate` 영속화 · `DISCOVERED → ANALYZING → ANALYZED` | 이슈 완료조건 |
| FR-4 | `implementationFeasible=false` · **저신뢰도**는 `REJECTED` 로 | 이슈 완료조건 |
| FR-5 | **재실행 멱등** — 같은 이슈에 후보가 두 번 생기지 않는다 | 이슈 완료조건 |
| FR-6 | `AgentRun` 에 stage·attempt·토큰 기록 | 이슈 완료조건 |
| FR-7 | 🔴 **AI 기여가 허용되지 않은 저장소는 분석하지 않는다** | S-5 · #7 이 #11 로 인계 |

> FR-7 은 이슈 본문에 없다. **#7 이 명시적으로 넘겼다** —
> `AnalyzeRepositoryPolicyUseCase.assertContributionAllowed` javadoc: 「호출자는 #11 · #24 다.
> 이 PR 은 단언을 제공하고 **강제는 그쪽에서 일어난다**」. 강제할 곳이 여기다.

#### 🔴 FR-1 의 대상은 `PASSED` **하나가 아니다** — #9 가 3상태로 만들었다

`FilterOutcome` javadoc(`issue/domain/FilterOutcome.java`)이 못 박아 뒀다.

```java
/**
 * 규칙으로 가를 수 없다 — <b>LLM 이 본다</b>(#11).
 * <p>배제가 아니므로 후보 생성 대상이다. 「판정하지 않았다」를 「통과」로 적지 않기 위해 존재한다.
 */
UNDECIDED,
```

`excluded()` 도 `this == REJECTED` **하나뿐**이다. 즉 배제된 것은 `REJECTED` 이고,
**`PASSED` 와 `UNDECIDED` 가 둘 다 분석 대상**이다.

🔴 `UNDECIDED` 를 빼면 **#9 의 3상태 설계가 무의미해진다.** 규칙으로 가를 수 없어 LLM 에게
넘기려고 만든 상태인데 소비자가 없으면 테이블에 영구히 고인다. 「판정하지 않았다」를
「통과」로 뭉개지 않으려 만든 구분이, 이번엔 **「판정하지 않았다」를 「없는 것」으로** 뭉개게 된다.

#### FR-1 의 정렬 — `filter_priority` 는 #11 을 위해 만들어졌다

`Issue.filterPriority` javadoc: 「라벨 우선순위 점수 (FR-4). **#11 이 분석 순서를 SQL 로
정렬할 때 쓴다**」.

배치 상한(`max-batches-per-run`)이 있으므로 **정렬이 곧 「어느 이슈에 토큰을 쓸지」의 결정**이다.
`id ASC`(수집 순서)로 두면 `good first issue` 가 뒤 배치로 밀린다 — 상한에 걸리면 영영 안 온다.

### 비기능 요구사항 (NFR)

| # | 요구 | 왜 |
|---|---|---|
| NFR-1 | 🔴 **LLM 호출이 트랜잭션 밖** | 분석 1건이 수십 초다. 배치로 돌면 커넥션이 그만큼 잡힌다 |
| NFR-2 | 배치 상한 (`batch-size` · `max-batches-per-run`) | 이슈 수천 건에 무한 루프를 만들지 않는다 |
| NFR-3 | 후보 1건당 LLM **1회** | 분석은 Q-6 의 재시도 카운터 **밖**이다. 전송 재시도만 허용 |
| NFR-4 | 한 이슈의 실패가 배치 전체를 죽이지 않는다 | 이슈 1건의 이상 응답으로 스캔 전체가 멈추면 안 된다 |

---

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키나 |
|---|---|---|
| **S-1** push 대상 | ❌ | 이 작업에 push·remote·Fork 좌표가 없다. 읽기와 DB 쓰기뿐 |
| **S-2** draft 고정 | ❌ | PR·코멘트 API 를 호출하지 않는다 |
| **S-3** 샌드박스 | ❌ | 대상 저장소 코드를 실행하지 않는다. 이슈 **텍스트**만 읽는다 |
| **S-4** 시크릿 | 🔴 **접촉** | 아래 ① |
| **S-5** 대상 저장소 규약 | 🔴 **접촉** | 아래 ② |
| **S-6** 승인 지점 | 🔴 **접촉** | 아래 ③ |

#### ① S-4 — 유출면이 **둘**이다

**나가는 쪽 (프롬프트)** — 이슈 `title`·`body` 는 대상 저장소 사람이 쓴 텍스트다.
**대상 저장소가 시크릿을 커밋해 뒀거나 이슈 본문에 붙여 넣었을 수 있다.**

`AnthropicLanguageModel` 이 `PromptScrubber` 를 **생성자 필수 인자로 강제**하므로
`LanguageModel` 을 통과하는 모든 프롬프트가 스크럽된다 — 우리가 따로 하지 않는다.
**대신 `LanguageModel` 을 우회하는 경로를 만들지 않는 것**이 이 PR 의 의무다.

**들어오는 쪽 (`analysis` 적재)** — LLM 응답을 `contribution_candidate.analysis`(`@ExternalText`)에
넣는다. 모델이 프롬프트의 토큰을 **되뱉을 수 있다.**

> 🔴 **#13 리뷰가 지적한 그 구멍이 여기다.** #13 은 조회 측에 `TokenRedactor.redact` 를 걸었지만
> 「`analysis` 에 대해서는 **한 겹**」이라고 스스로 적었다 — 적재 측 방어가 없었기 때문이다.
> **이 PR 이 그 1차 방어를 만든다.** 적재 시점에 `TokenRedactor.redact` 를 거친다.

#### ② S-5 — 게이트를 **여기서 강제**한다 (FR-7)

🔴 **그래서 이 UseCase 는 저장소 스코프다** — `analyze(Long repositoryId)`.

```java
public AnalysisResult analyze(Long repositoryId)   // ← 전역 배치가 아니다
```

`assertContributionAllowed(repositoryId)` 를 **배치 시작 전 1회** 호출하고,
`NOT_ANALYZED`·`UNDETERMINED`(보류)·`FORBIDDEN` 이면 `ContributionNotAllowedException` 으로 중단한다.

**전역 배치로 두면 게이트가 성립하지 않는다.** 여러 저장소가 한 배치에 섞이면 1회 호출이
나머지 저장소를 덮지 못하고, 한 저장소가 보류라고 **다른 저장소 이슈까지 멈추는** 모순이 생긴다
(NFR-4 와 정면 충돌). 기존 코드도 전부 저장소 스코프다 —
`IssueJpaRepository.findByRepositoryIdAndFilterResultIsNull` · `assertContributionAllowed(Long)`.

**저장소 단위로 한 번만 부르는 이유** — 정책은 저장소당 1건이고 배치 안에서 바뀌지 않는다.
이슈마다 부르면 같은 쿼리를 N 번 친다.

⚠️ **게이트 실패는 NFR-4 의 「한 이슈의 실패」가 아니다.** 저장소 전체가 대상이 아니라는
판정이므로 **배치를 시작하지 않고 중단**하는 것이 맞다. NFR-4 가 말하는 것은 이슈 단위 실패다.

⚠️ **「보류를 허용으로 읽지 않는다」가 이 게이트의 전부다.** `isAiContributionUndetermined()` 는
`FORBIDDEN` 과 **같이** 막는다. 「아직 판정 안 됨이니 일단 분석은 해 두자」로 풀면 S-5 가 무너진다.

#### ③ S-6 — `SELECTED` 로 자동 전이하지 않는다

이 UseCase 가 만드는 상태는 **`ANALYZED` 또는 `REJECTED` 또는 `FAILED`** 뿐이다.

- `selectByHuman` 을 **부르지 않는다.** 이것을 **테스트로 고정**한다
- `REJECTED` 로는 `rejectAsInfeasible`(시스템 판정)만 쓴다. ⚠️ `cancelSelection`(사람)과
  **메서드는 다르되 저장되는 상태는 같다** — 둘 다 `REJECTED` 종단이고, 「시스템이 뺐는지
  사람이 뺐는지」를 사후에 가를 데이터가 DB 에 없다. 감사 추적이 필요하면 별도 이슈다
- 분석 실패는 `failAnalysis` → `FAILED` 종단. Q-6 확정대로 **파이프라인 재시도를 붙이지 않는다**

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리나 | 처리 |
|---|---|---|
| **Q-6** 재시도 단위 | ✅ **확정됨** | `ANALYZE` 는 카운터 **밖**. `AgentRunContext.firstAttempt` (attempt 고정 1) · 후보 `attempt` 를 건드리지 않는다(0 유지) |
| **Q-8** AI 기여 판정 | ✅ **확정됨** | #7 이 판정을, 이 PR 이 **강제**를 맡는다 (FR-7) |
| **Q-9** 테스트 대역 | ✅ **확정됨** | 3계층 중 **능력 페이크**(`FakeIssueAnalyst`)와 **어댑터 매핑**(페이크 `LanguageModel`). 전송 계약 층은 #10 이 이미 덮었다 |
| **Q-11** SDK vs 직접 | ❌ | 새 대외 의존을 붙이지 않는다 — `LanguageModel` 위에 얹는다 |
| **Q-3** 프로필 분리 | 🟡 **스침** | 배치가 API 스레드를 점유하는 문제는 **#14(스케줄러 배선)에서** 드러난다. 이 PR 은 UseCase 만 만들고 트리거를 만들지 않는다 — 판단을 앞서가지 않는다 |
| Q-1 · Q-2 · Q-4 · Q-5 · Q-7 · Q-10 | ❌ | 접촉 없음 |

### ⚠️ 가정 2개 — 확인 불가라 명시하고 진행한다

| # | 가정 | 근거 · 되돌리는 법 |
|---|---|---|
| A-1 | **저신뢰도 임계 = `0.50`** | 이슈가 「저신뢰도」라고만 적고 값을 안 줬다. 실측 데이터가 0건이라 지금 정하는 어떤 값도 추정이다. **설정값**(`agent.analysis.min-confidence`)으로 빼 두어 데이터가 쌓이면 코드 수정 없이 바꾼다 |
| A-2 | **`testRequired` 는 컬럼을 만들지 않는다** | PRD §11 산출물에 있으나 `contribution_candidate` 에 대응 컬럼이 **없다**(V2 생성 · V4 가 `attempt`·`version` 만 추가). **소비자가 없다** — 쓸 주체는 구현 단계(#16~#19)이고 아직 착수 전이다. `analysis` 요약 텍스트에 남기고, 필요해지는 이슈에서 컬럼을 추가한다 |

⚠️ **A-1 의 「설정값이라 되돌리기 쉽다」는 이미 걸러진 후보에는 소급되지 않는다.**
`REJECTED` 는 종단이고(`allowedNext` 비어 있음), 재분석 경로도 없다(R-7).
임계를 낮춰도 **이미 `REJECTED` 된 후보는 돌아오지 않는다.** 처음에 **느슨하게 잡는 편이
안전한 방향**이라 `0.50` 으로 둔다 — 조이는 것은 언제든 되지만 푸는 것은 소급되지 않는다.

---

## 3. 스코프

| 포함 | 제외 |
|---|---|
| `IssueAnalyst` 능력 + LLM 구현 | **스캔·스케줄러 트리거** — #14 |
| `AnalyzeIssuesUseCase` (배치) | **후보 선택 API** — #24 |
| 후보 생성·전이·분석 결과 적재 | **`repository_id` 비정규화** — #13 이 넘긴 항목. V8 이 필요하고 소비자(#13 저장소 필터)가 아직 없다 |
| S-5 게이트 강제 | **`confidence` 를 쓰는 정렬·랭킹** — #24 |
| 적재 측 `analysis` 스크럽 | `attempt` 를 올리는 경로 — 구현 단계(#18) 몫 |

**HTTP 엔드포인트를 만들지 않는다.** #14 가 `POST /scan` 에서 이 UseCase 를 부른다.
지금 엔드포인트를 만들면 #14 가 지우게 된다.

---

## 4. 기술 설계

### 변경 파일

| 파일 | 구분 | 내용 |
|---|---|---|
| `candidate/domain/IssueAnalysis.java` | 신규 | 분석 결과 **값 타입** + 스키마 불변식 |
| `candidate/domain/IssueAnalyst.java` | 신규 | 🔴 **능력** 선언 (능력 이름) |
| `candidate/domain/AnalysisRejectedException.java` | 신규 | 스키마 검증 실패 |
| `candidate/domain/ContributionCandidate.java` | 수정 | ⚠️ **계약 표면** — `completeAnalysis` 시그니처 |
| `candidate/adapter/out/llm/LlmIssueAnalyst.java` | 신규 | 기술 구현 (기술 이름) |
| `candidate/adapter/out/llm/IssueAnalysisProperties.java` | 신규 | 임계·상한 |
| `candidate/adapter/out/persistence/ContributionCandidateRepository.java` | 수정 | `findIssueIdsIn` 추가 |
| `candidate/application/AnalyzeIssuesUseCase.java` | 신규 | 배치 조율 · **트랜잭션 분할** |
| `candidate/application/AnalysisResult.java` | 신규 | 배치 집계 반환값 |
| `candidate/application/CandidateAnalysisWriter.java` | 신규 | 짧은 트랜잭션 2개 |
| `issue/application/FindAnalyzableIssuesUseCase.java` | 신규 | 규율 ④ — 이슈 공급 |
| `issue/domain/AnalyzableIssue.java` | 신규 | 규율 ④ — 값 타입 |
| `issue/adapter/out/persistence/IssueJpaRepository.java` | 수정 | 분석 대상 키셋 페이지 쿼리 |
| `config/IssueAnalysisConfig.java` | 신규 | 빈 조립 |
| `src/main/resources/application.yml` | 수정 | `agent.analysis.*` |

**마이그레이션 없음** — `contribution_candidate` 의 컬럼을 전부 그대로 쓴다 (A-2).

### 설계 ① — 능력은 `candidate/domain`, 구현은 `candidate/adapter/out/llm` (규율 ③)

#7 의 `ContributionRuleInterpreter` / `LlmContributionRuleInterpreter` 와 **같은 배치**다.
`LanguageModel` 위에 얹히는 **2층 능력**이다.

```java
// candidate/domain — 능력 이름. LLM 이라는 단어가 없다
public interface IssueAnalyst {
    IssueAnalysis analyze(Long candidateId, AnalyzableIssue issue);
}
```

`candidateId` 를 받는 이유 — `AgentRunContext` 가 `ANALYZE` 에 `candidateId` 를 **필수**로 요구한다
(「기록을 붙일 대상이 없다」). 그래서 **후보를 먼저 만들고 분석한다** — 설계 ③.

### 설계 ② — 🔴 모델에게 **판정을 묻지 않는다.** 관찰을 묻고 번역은 우리가 한다

#7 이 세운 원칙을 그대로 따른다. 모델에게 `"REJECTED 인가?"` 를 물으면 **임계 정책이 모델 안으로
들어가** 우리가 바꿀 수 없게 된다.

물어보는 것은 PRD §11 의 **관찰값 7개**뿐이다.

```json
{ "category": "...", "difficulty": "EASY|MEDIUM|HARD", "implementationFeasible": true,
  "estimatedFiles": 4, "estimatedLoc": 120, "testRequired": true,
  "breakingChange": false, "confidence": 0.87, "summary": "..." }
```

`summary` 는 PRD §11 산출물에 **없는 필드**다. 넣는 이유 둘 —
`analysis` 컬럼에 담을 사람이 읽을 요약이 필요하고(#13 조회 API 가 이미 노출한다),
A-2 가 `testRequired` 를 컬럼 대신 여기에 남기기로 했기 때문이다.

```
```

`REJECTED` 로 번역하는 것은 **UseCase** 다 (설계 ⑤).

**스키마 검증 = 파싱 실패를 성공으로 처리하지 않는다** (FR-2). 아래 중 하나라도 어긋나면
`AnalysisRejectedException` 이고, 결과는 `FAILED` 다.

| 검사 | 왜 |
|---|---|
| JSON 객체 1개로 파싱되는가 | 코드펜스·머리말이 섞이면 실패다 |
| `difficulty` 가 `EASY|MEDIUM|HARD` 인가 | 모르는 값을 DB 에 넣지 않는다 |
| `confidence` 가 `0.00 ~ 1.00` 인가 | 컬럼이 `NUMERIC(3,2)` 다. 1.5 를 넣으면 적재 시점에 터진다 |
| `implementationFeasible` 이 `null` 이 아닌가 | 🔴 **`null` 을 `false` 로 읽지 않는다.** 판정이 안 선 것이다 |
| `estimatedFiles`·`estimatedLoc` 이 음수가 아닌가 | — |

⚠️ **응답 절단(`TRUNCATED`)은 성공이 아니다.** `LlmException` 으로 올라오므로 그대로 실패다 —
잘린 JSON 을 파서에 넣지 않는다.

⚠️ **스키마 검증 실패는 `AgentRun` 에 `SUCCEEDED` 로 남는다.** `RecordingLanguageModel` 은
「LLM 호출이 성공했는가」를 기록하는데, 검증은 그 **뒤**에 우리가 하기 때문이다.
결과적으로 **`AgentRun=SUCCEEDED` · 후보=`FAILED`** 인 행 조합이 정상적으로 생긴다.

이것을 「버그」로 보고 맞추려 하지 않는다 — 둘은 **다른 것을 세는 장부**다.
`AgentRun` 은 **비용**(토큰을 실제로 태웠는가)이고 후보 상태는 **파이프라인 판정**이다.
호출은 성공했고 토큰도 나갔다. 여기서 `AgentRun` 을 `FAILED` 로 적으면 **비용 장부가 거짓말을 한다.**
대신 후보의 `analysis` 에 거절 사유를 남겨 사람이 이을 수 있게 한다.

### 설계 ③ — 트랜잭션을 **둘로 가른다** (NFR-1 · 규율 🔴)

```
   ┌─ TX1 (짧다) ──────────────┐
   │ discover(issueId)          │   UNIQUE(issue_id) 가 멱등을 강제한다
   │ startAnalysis()            │   DISCOVERED → ANALYZING
   └────────────────────────────┘
                 ↓
   ╔═ 트랜잭션 밖 ══════════════╗
   ║ IssueAnalyst.analyze(...)  ║   🔴 LLM. 수십 초. AgentRun 기록은
   ║   → RecordingLanguageModel ║      데코레이터가 자동으로 남긴다 (FR-6)
   ╚════════════════════════════╝
                 ↓
   ┌─ TX2 (짧다) ──────────────┐
   │ 성공 → completeAnalysis(결과)  → ANALYZED
   │        (+ 임계 미달이면 rejectAsInfeasible → REJECTED)
   │ 실패 → failAnalysis()          → FAILED
   └────────────────────────────┘
```

**`ANALYZING` 이 「LLM 을 부르는 중」을 DB 에 남긴다.** 이 중간 상태가 없으면 프로세스가 죽었을 때
후보가 `DISCOVERED` 로 남아 **다음 실행이 같은 이슈를 또 분석한다**(토큰을 두 번 태운다).

⚠️ **`ANALYZING` 에 박힌 후보를 이 PR 이 자동 회수하지 않는다.** 자동 회수는 「시간이 지나면
상태가 바뀐다」를 만드는 것이고, 그것은 승인 지점 설계(#24)와 함께 정할 일이다. **박힌 것이
보이는 것**이 지금은 더 낫다 — #13 조회 API 가 `status=ANALYZING` 으로 보여준다.

### 설계 ④ — 멱등은 **DB 가 강제한다** (FR-5)

```
① 후보가 없는 분석 대상 이슈를 고른다  ← 낙관적 선별 (경쟁에 진다)
② discover() + save()              ← 🔴 UNIQUE(issue_id) 가 정본
   └ DataIntegrityViolationException → 그 이슈는 건너뛴다
```

🔴 **「존재 확인 후 삽입」만으로는 멱등이 아니다.** 두 워커가 동시에 ①을 통과하면 둘 다 삽입을
시도한다. `UNIQUE(issue_id)` 제약이 한쪽을 떨어뜨리고, **그 예외를 정상 흐름으로 삼키는 것**이
멱등의 실체다. 확인 쿼리는 LLM 호출을 아끼는 최적화일 뿐 방어가 아니다.

「같은 이슈에 후보가 두 번 생기면 **PR 도 두 번 나간다**」 — 되돌릴 수 없는 사고다.

### 설계 ⑤ — `REJECTED` 판정은 **UseCase 가** 한다 (FR-4)

```
implementationFeasible == false   → REJECTED
confidence < min-confidence       → REJECTED
그 외                              → ANALYZED
```

전이 경로는 `ANALYZING → ANALYZED → REJECTED` **2단**이다 — 상태머신이
`ANALYZING → REJECTED` 를 허용하지 않는다(#12). **우회하지 않는다.**
`ANALYZED` 를 거쳐 가므로 **분석 결과는 어느 쪽이든 DB 에 남는다** — 왜 걸러졌는지 보인다.

### 설계 ⑥ — ⚠️ 계약 표면: `completeAnalysis` 가 결과를 **받는다**

```java
// before (#12)                           // after (#11)
StatusTransition completeAnalysis(Clock)  StatusTransition completeAnalysis(IssueAnalysis, Clock)
```

**분석 결과 없이 `ANALYZED` 가 되는 경로를 남기지 않는다.** 필드 setter 를 따로 만들면
「`ANALYZED` 인데 `confidence` 가 `null`」 이 만들어지고, Q-7 이 `@Setter` 를 금지한 이유가
그대로 되살아난다. **결과 적재와 전이를 한 메서드로 묶는 것**이 불변식이다.

호출자는 현재 **테스트뿐**이다(운영 코드 0곳) — #12 가 전이만 깔아 뒀기 때문이다.

#### 🔴 `analysis` 스크럽은 **도메인 안**에서 한다 — UseCase 를 믿지 않는다

`completeAnalysis` 가 필드를 채울 때 `TokenRedactor.redact` 를 **마지막 그물**로 건다.

**같은 애그리거트의 옆 엔티티가 이미 그렇게 하고 있다** — `AgentRun.fail`:

```java
// 🔴 마지막 그물이다. 호출자가 「우리 어휘만 넣는다」는 규약을 지키는 것이 1차 방어이나,
// 규약은 언젠가 깨진다 …
this.errorMessage = TokenRedactor.redact(reason);
```

`ScrubbedRules` javadoc 도 같은 말을 한다 — 「`String` 을 받는 setter 를 열어 두면
**「이번엔 괜찮겠지」가 언젠가 들어온다**. `RepositoryPolicy` 가 이 타입만 받게 해서
**스크럽을 우회할 경로를 없앤다** — `AnthropicLanguageModel` 이 생성자로 스크러버를 강제한 것과
같은 수법이다」.

`contribution_candidate.analysis` 는 `repository_policy.contribution_rules` 와 **조건이 똑같다**:
`@ExternalText` 가 붙은 TEXT 컬럼 · LLM 응답 원문 · 하류(#23 PR 본문) 입력 후보.
**같은 조건에 다른 방어를 적용할 이유가 없다.**

⚠️ UseCase 쪽 스크럽도 남긴다(1차 방어). 도메인은 **그것이 빠졌을 때 걸리는 그물**이지
대체재가 아니다 — `AgentRun` 이 취한 것과 같은 2중 구조다.

### 설계 ⑦ — 규율 ④: `candidate` 가 `issue` 엔티티를 **import 하지 않는다**

```
candidate/application/AnalyzeIssuesUseCase
        │ 호출
        ▼
issue/application/FindAnalyzableIssuesUseCase   ──▶  issue/domain/AnalyzableIssue (값 타입)
```

`AnalyzableIssue(id, repositoryId, githubIssueNumber, title, body, labels, url)` — **값**이다.
`Issue` 엔티티도 `IssueJpaRepository` 도 `candidate` 로 넘어가지 않는다.

```java
// issue/application
List<AnalyzableIssue> findAnalyzable(Long repositoryId, Short afterPriority, Long afterId, int size);
```

**정렬은 `filter_priority DESC, id ASC`** 이고 커서도 그 복합키다 (FR-1).

```sql
WHERE i.repositoryId = :repositoryId
  AND i.filterResult IN ('PASSED', 'UNDECIDED')
  AND ( :afterId IS NULL
        OR COALESCE(i.filterPriority, 0) <  :afterPriority
        OR (COALESCE(i.filterPriority, 0) = :afterPriority AND i.id > :afterId) )
ORDER BY COALESCE(i.filterPriority, 0) DESC, i.id ASC
```

🔴 **`COALESCE` 가 장식이 아니다.** `filter_priority` 는 nullable 인데
**`ORDER BY … DESC` 의 NULL 위치가 H2 와 PostgreSQL 에서 갈린다.** 벤더 고유 문법을 쓰지
않기로 한 Q-2 의 제약이 정확히 여기에 걸린다 — `NULLS LAST` 를 쓰는 대신 `COALESCE` 로
**값 자체를 결정적으로** 만든다. 커서 비교식에도 같은 식을 써야 정렬과 어긋나지 않는다.

`ORDER BY` 없는 `LIMIT/OFFSET` 이 같은 행을 두 번 주는 함정은 #13 이 이미 맞았다.
여기서는 **OFFSET 자체를 쓰지 않는다** — 후보 생성으로 대상 집합이 줄어들기 때문에
OFFSET 은 행을 건너뛴다. 키셋 커서여야 한다.

### 체크리스트 답변

| # | 항목 | 답 |
|---|---|---|
| 1 | 소유 도메인 | 후보 생성·분석 결과는 **`candidate`**. 이슈 공급은 **`issue`**. 능력 실행 기록은 `agent`(기존) |
| 2 | 레이어 배치 | 값·능력·불변식 → `domain` / 배치 조율·트랜잭션 → `application` / LLM·JPA → `adapter/out` |
| 3 | 능력 인터페이스 | ✅ `IssueAnalyst`(능력) ↔ `LlmIssueAnalyst`(기술) |
| 4 | 🔴 트랜잭션 안 대외 호출 | **없다** — 설계 ③ |
| 5 | 상태 전이 영향 | `DISCOVERED→ANALYZING→ANALYZED→REJECTED` · `ANALYZING→FAILED`. **전이를 새로 만들지 않는다** — #12 가 정의한 것만 쓴다 |
| 6 | 멱등 | ✅ `UNIQUE(issue_id)` — 설계 ④ |
| 7 | `Clock` 주입 | ✅ 전이 메서드가 전부 `Clock` 을 받는다. `Instant.now()` 직접 호출 0 |
| 8 | 🔴 안전 경계 | S-4 · S-5 · S-6 — §2 |

### 설정

```yaml
agent:
  analysis:
    batch-size: 50              # 한 번에 집어 오는 분석 대상 이슈 수
    max-batches-per-run: 20     # 한 실행의 상한 — 최대 1,000건
    max-output-tokens: 1500     # 관찰값 7개 + 요약. 16000 은 과하다
    min-confidence: 0.50        # A-1 — 미만이면 REJECTED
    max-body-chars: 20000       # 이슈 본문 절단. 저장소 전체를 넣지 않는다(PRD §12)와 같은 정신
```

**새 환경변수 없음** → `.env.example` 변경 없음.

---

## 5. 구현 순서

### 실행 모드: sequential

값 → 능력 → 구현 → 조율 순으로 **아래 단계가 위 단계를 import** 한다. 병렬 조건(수정 파일
겹침 없음 · import 의존 없음)을 만족하지 못한다.

| 단계 | 내용 | 선행 |
|---|---|---|
| 1 | `IssueAnalysis` · `AnalyzableIssue` · `AnalysisRejectedException` | — |
| 2 | `ContributionCandidate.completeAnalysis` 시그니처 변경 + 기존 테스트 정정 | 1 |
| 3 | `IssueAnalyst` 능력 + `FindAnalyzableIssuesUseCase` + JPA 쿼리 2개 | 1 |
| 4 | `LlmIssueAnalyst` + `IssueAnalysisProperties` + 빈 조립 | 3 |
| 5 | `CandidateAnalysisWriter`(TX1·TX2) + `AnalyzeIssuesUseCase` + S-5 게이트 | 2·4 |
| 6 | 통합 테스트 · 문서 동기화 | 5 |

---

## 6. 테스트 계획

### 🔴 안전 경계 — 커버리지 100% 대상

| 테스트 | 무엇을 고정하나 |
|---|---|
| `분석은_SELECTED로_자동_전이하지_않는다_S6` | 배치를 끝까지 돌린 뒤 `selectedAt` 이 **전부 null** · `SELECTED` 0건 |
| `AI기여가_금지된_저장소는_분석하지_않는다_S5` | `FORBIDDEN` → 후보 0건 · **LLM 호출 0회** |
| `규약이_보류인_저장소는_분석하지_않는다_S5` | 🔴 `UNDETERMINED`(null)를 **허용으로 읽지 않는다** |
| `규약을_분석한_적_없는_저장소는_분석하지_않는다_S5` | `NOT_ANALYZED` |
| `analysis_의_토큰이_적재되지_않는다_S4` | 모델이 토큰을 되뱉어도 DB 에 원문이 안 들어간다 |
| `UseCase_를_건너뛰어도_analysis_가_스크럽된다_S4` | 🔴 **도메인 그물** — `completeAnalysis` 를 **직접** 호출해도 원문이 안 남는다 (설계 ⑥) |
| `분석_경로에_LanguageModel_외의_송신이_없다_S4` | `LlmIssueAnalyst` 가 HTTP 클라이언트를 필드로 갖지 않는다 — 스크럽 강제를 우회하는 경로가 생기지 않는다 |
| `분석_실패는_FAILED_로_끝난다_S6` | 종단. 재시도 루프가 생기지 않는다 |

`FORBIDDEN` 테스트에서 **LLM 호출 0회**를 단언하는 것이 핵심이다. 「후보가 안 생겼다」만 보면
**분석은 다 하고 저장만 안 하는 구현**도 통과한다 — 토큰은 그대로 태운다.

### 유닛

| 대상 | 케이스 |
|---|---|
| `IssueAnalysis` | `difficulty` 미지값 · `confidence` 범위 밖 · `implementationFeasible=null` → 거부 |
| `ContributionCandidate` | `completeAnalysis` 가 필드를 채운다 · `ANALYZING` 밖에서 부르면 거부 |
| `LlmIssueAnalyst` | 페이크 `LanguageModel` 로 — 정상 / 코드펜스 / 깨진 JSON / 미지 enum / 범위 밖 숫자 |
| 임계 판정 | `feasible=false` → REJECTED · `confidence` 경계값(`0.50` 포함/미만) |

### 통합 (`@AgentIntegrationTest`)

| 케이스 | 단언 |
|---|---|
| 정상 배치 | `PASSED` N건 → 후보 N건 · 상태 `ANALYZED` · `AgentRun(ANALYZE, attempt=1)` N건 |
| 🔴 **`UNDECIDED` 도 분석된다** | `UNDECIDED` 이슈가 후보가 된다 — #9 인계분(FR-1) |
| 🔴 **`REJECTED` 는 분석되지 않는다** | 배제된 이슈에 토큰을 쓰지 않는다 |
| **우선순위 순서** | `filter_priority` 가 높은 이슈가 **먼저** 분석된다 · `filterPriority=NULL` 이 섞여도 순서가 결정적이다 |
| 배치 상한 | `max-batches-per-run` 을 넘겨 돌지 않는다 · 커서가 전진해 같은 이슈를 두 번 집지 않는다 |
| 스키마 검증 실패 | 후보 `FAILED` · **`AgentRun` 은 `SUCCEEDED`** (설계 ② — 장부가 다르다) |
| **재실행 멱등** | 두 번 돌려도 후보 N건 유지 · **LLM 호출이 2회차에 0회** |
| 저신뢰도 | `REJECTED` · 분석 결과는 **남아 있다** |
| 한 건 실패 | 그 후보만 `FAILED`, 나머지는 `ANALYZED` (NFR-4) |
| 후보 `attempt` | `ANALYZE` 를 돌려도 **0 유지** (Q-6) |

### 대역

`FakeIssueAnalyst`(`@FakeAdapter`, `candidate/domain` 의 `src/test`) — 호출을 **세고**
실패 모드(스키마 거부·`LlmException`)를 재현한다. 「항상 성공만 반환하는 페이크」는
S-6 게이트를 검증하지 못한다.

⚠️ 대역은 싱글턴이고 컨텍스트가 캐시된다. **호출 횟수를 단언하는 테스트는 `@BeforeEach` 에서 초기화**한다.

---

## 7. 리스크

| # | 리스크 | 완화 |
|---|---|---|
| R-1 | 🔴 **PR #54(#28)와 `TokenRedactor` 충돌** | #54 는 `support/secret/*`·`issue/domain/IssueSnapshot` 을 고치고 이 PR 은 **호출만** 한다. 파일이 겹치지 않는다. #54 가 먼저 머지되면 rebase 시 API 변화만 확인 |
| R-2 | #54 의 `ExternalTextScrubRegistryTest` 가 이 PR 의 새 적재 경로를 잡는다 | **바람직한 방향이다.** 그 테스트가 요구하는 대로 스크럽을 넣는다 |
| R-3 | 임계 `0.50` 이 과하거나 느슨 | A-1 — 설정값. 데이터가 쌓이면 조정 |
| R-4 | 이슈 본문이 매우 길어 토큰이 터진다 | `max-body-chars` 로 절단. 절단 사실을 프롬프트에 명시해 모델이 「전문을 봤다」고 오인하지 않게 한다 |
| R-5 | 배치가 API 스레드를 점유 | **이 PR 은 트리거를 만들지 않는다.** Q-3 판단은 #14 |
| R-6 | `ANALYZING` 에 박힌 후보 | 설계 ③ — 자동 회수하지 않고 조회로 드러낸다. 회수 경로는 #24 |
| R-7 | 🔴 **이슈 내용이 바뀌어도 재분석되지 않는다** | 아래 |
| R-8 | `filter_priority` 복합 커서가 H2·PostgreSQL 에서 갈린다 | `COALESCE` 로 NULL 을 제거해 결정적으로 만든다(설계 ⑦). **양쪽에서 순서를 단언하는 테스트**로 고정 — Q-2b-1 이 경고한 자리다 |

#### R-7 — 알려진 한계로 남긴다

`Issue.updateFrom` 은 본문이 바뀌면 `clearFilterVerdict()` 로 `filter_result` 를 `NULL` 로
되돌리고, #9 가 다시 판정한다. 그런데 FR-1 의 「**후보가 없는**」 조건 때문에
**이미 후보가 있는 이슈는 다시 분석되지 않는다.** 후보의 `analysis` 는 옛 본문 기준으로 굳는다.

**이 PR 에서 고치지 않는다.** 고치려면 「재분석」이 무엇인지 정해야 하는데 —
기존 후보를 덮는가(이력이 사라진다) / 새 후보를 만드는가(`UNIQUE(issue_id)` 와 FR-5 에 정면 충돌) —
둘 다 멱등 설계를 다시 그리는 일이다. **기록되지 않는 것이 문제이지 지금 푸는 것이 답은 아니다.**

⚠️ A-1 과 맞물린다 — `REJECTED` 된 후보는 종단이고 재분석 경로도 없으므로 **되돌아오지 않는다.**

---

## 8. 복잡도

| 단계 | 파일 수 | 복잡도 |
|---|---|---|
| 1 값 타입 | 3 | 낮음 |
| 2 도메인 계약 변경 | 1 (+테스트) | **중간** — 계약 표면 |
| 3 능력·쿼리 | 4 | 낮음 |
| 4 LLM 어댑터 | 3 | **중간** — 스키마 검증이 본체 |
| 5 UseCase | 3 | **높음** — 트랜잭션 분할 · 멱등 · 게이트 |
| 6 테스트·문서 | ~8 | 중간 |

예상 diff **600~800줄** — PR 컨벤션 권장(400줄)을 넘는다. **분할하지 않는다**:
능력·구현·조율을 가르면 어느 PR 도 단독으로 동작하지 않고, 상태 전이가 반쯤 열린 채 머지된다.
대신 리뷰 네비게이션을 PR 본문에 넣는다 (도메인 → 어댑터 → UseCase 순).

---

## 9. 문서 동기화 대상

| 문서 | 왜 |
|---|---|
| `.claude/codemaps/domain.md` | `ANALYZING→ANALYZED→REJECTED` 경로에 **실제 호출자가 생긴다** |
| `.claude/codemaps/architecture.md` | `candidate/adapter/out/llm` 신설 |
| `.claude/rules/context/glossary.md` | 능력 표에 `IssueAnalyst` / `LlmIssueAnalyst` 추가 |
| `.claude/rules/context/project-overview.md` | 「`GET /api/candidates` 는 **#11 이 없어 결과가 비어 있다**」 문장을 정정 |
| `.claude/rules/context/external-deps.md` | LLM 4개 호출 지점 중 `ANALYZE` 가 **실제로 생겼다** |
| `docs/plans/PLAN-13.md` | 인계 항목(`analysis` 적재 측 스크럽)이 닫힘 — `repository_id` 비정규화는 **여전히 열림** |
| `.env.example` | ❌ 변경 없음 (새 환경변수 없음) |
| `README.md` | ❌ 변경 없음 (디렉토리 구조 불변 — 도메인 패키지 추가 없음) |

---

## 10. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-26 | smileboy0014 | 초안 |
| 2026-09-26 | smileboy0014 | rev 2 — `gap-analyzer` 검토 반영. 🔴 `UNDECIDED` 누락(#9 인계분) · 🔴 S-4 적재 측을 도메인 그물로 · 저장소 스코프 고정 · `filter_priority` 정렬 · A-2 근거 정정 · R-7·R-8 신설 |
