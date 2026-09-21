# 도메인 코드맵

> 기준 — [PRD](../../docs/ai-oss-contributor-agent-prd.md) §9 Issue Discovery · §10 Candidate State Machine · §11 Issue Analysis · §15 Verification Pipeline · §17 Retry Strategy (v1.1 Draft).
> 비즈니스 규칙과 상태머신. **구현 전에 이 맵을 확인하고 일치시킬 것.**
> ⚠️ 코드에 존재하는 것은 `CandidateStatus` enum 뿐이다. 전이 로직·필터·검증 파이프라인은 전부 미구현이다.

## 파이프라인

```
탐색 ─────▶ 분석 ─────▶ 선택 ─────▶ 구현 ─────▶ 검증 ─────▶ Draft PR ─────▶ 사람
이슈 수집   기여 가능성   ★사람★     코드 수정    빌드·테스트   Fork 에 push    ★사람★
           (LLM)                  (LLM·샌박)   (샌드박스)                   제출 판단
```

★ 표시 둘이 **사람의 자리**다. 자동으로 통과시키는 경로를 만들지 않는다 — [S-6](../rules/context/safety-boundaries.md).

---

## 후보 상태머신

```
                         [신규 수집]
                              │
                              ▼
                       ┌─────────────┐
                       │ DISCOVERED  │
                       └──────┬──────┘
                              │ 분석 시작
                              ▼
                       ┌─────────────┐
                       │  ANALYZING  │
                       └──────┬──────┘
                              │ LLM 판정 완료
                              ▼
                       ┌─────────────┐   feasible=false
                       │  ANALYZED   │──────────────────▶┌────────────┐
                       └──────┬──────┘   breaking=true   │ REJECTED ● │
                              │                          └────────────┘
                              │ ★사람이 고른다★
                              ▼
                       ┌─────────────┐
                       │  SELECTED   │
                       └──────┬──────┘
                              │
              ┌───────────────▼───────────────┐
              │        ┌──────────────┐       │
              │   ┌───▶│ IMPLEMENTING │       │
              │   │    └──────┬───────┘       │
              │   │           │ 코드 생성 완료   │
              │   │           ▼               │
              │   │    ┌──────────────┐       │
              │   ├────│   TESTING    │       │  재시도 루프
              │   │    └──────┬───────┘       │  (최대 3회)
              │   │  테스트 실패 │ 통과          │
              │   │           ▼               │
              │   │    ┌──────────────┐       │
              │   └────│  REVIEWING   │       │
              │     리뷰 └──────┬───────┘       │
              │     실패       │ 리뷰 통과      │
              └────────────────┼───────────────┘
                               │        재시도 상한 소진
                               │        ────────────────▶ ┌──────────┐
                               ▼                          │ FAILED ● │
                       ┌──────────────┐                   └──────────┘
                       │ READY_FOR_PR │
                       └──────┬───────┘
                              │ Fork push + Draft PR 생성
                              ▼
                       ┌───────────────┐
                       │ PR_CREATED ●  │
                       └───────────────┘
```

### 전이 표

| From | To | 트리거 | 주체 |
|---|---|---|---|
| — | `DISCOVERED` | 필터 통과한 이슈로 후보 생성 | 스캐너 |
| `DISCOVERED` | `ANALYZING` | 분석 요청 (`POST /candidates/{id}/analyze`) | 시스템 |
| `ANALYZING` | `ANALYZED` | LLM 분석 산출물 저장 | 시스템 |
| `ANALYZED` | `REJECTED` ● | `implementation_feasible=false` 또는 `breaking_change=true` | 시스템 |
| `ANALYZED` | `SELECTED` | **사람이 고른다** | **사람** |
| `SELECTED` | `IMPLEMENTING` | 구현 요청 (`POST /candidates/{id}/implement`) | 시스템 |
| `IMPLEMENTING` | `TESTING` | 코드 생성 완료 | 시스템 |
| `TESTING` | `REVIEWING` | 빌드·테스트 통과 | 시스템 |
| `TESTING` | `IMPLEMENTING` | 테스트 실패 → 에러 분석 후 재시도 | 시스템 |
| `REVIEWING` | `READY_FOR_PR` | AI 리뷰 통과 | 시스템 |
| `REVIEWING` | `IMPLEMENTING` | 리뷰 실패 → 재시도 | 시스템 |
| `IMPLEMENTING`·`TESTING`·`REVIEWING` | `FAILED` ● | **재시도 상한 소진** | 시스템 |
| `READY_FOR_PR` | `PR_CREATED` ● | Fork push + **draft** PR 생성 성공 | 시스템 |

● = **종단 상태**. `PR_CREATED` · `REJECTED` · `FAILED` 셋이다.

---

## 불변식

깨지면 제품 정의가 무너지거나 외부 커뮤니티에 사고가 나간다. 전부 **리뷰 무조건 블로킹**이다.

| # | 불변식 | 깨지면 | 근거 |
|---|---|---|---|
| 1 | **종단 상태에서 나가는 전이가 없다** | `PR_CREATED` 후보가 다시 구현 루프에 들어가 같은 PR 을 덮어쓴다 | S-6 |
| 2 | **`SELECTED` 는 사람 행위로만 도달한다** | 스케줄러가 발견부터 PR 까지 자동으로 흘려보낸다. 제품 정의 붕괴 | S-6 |
| 3 | **PR 은 항상 `draft`** | 검증 안 된 AI 코드가 메인테이너 리뷰 큐에 올라간다 = 스팸 | S-2 |
| 4 | **push 대상은 Fork 뿐** | 남의 저장소 히스토리 오염. 되돌릴 수 없다 | S-1 |
| 5 | **대상 저장소 실행은 샌드박스 안** | 악의적 저장소 하나로 호스트 장악 | S-3 |
| 6 | **`RepositoryPolicy` 없이 구현 단계로 못 간다** | 규약 위반 PR 은 읽히지 않고 닫힌다 | S-5 |
| 7 | **`ai_contribution_allowed` 판정 실패는 「보류」다** | AI 기여를 금지한 저장소에 PR 을 연다 | S-5 · Q-8 |
| 8 | **재시도 상한을 무한으로 바꾸지 않는다** | LLM 비용이 조용히 폭주하고 `FAILED` 신호가 사라진다 | S-6 |
| 9 | **후보는 이슈당 1건** | 같은 작업 이중 실행 · 중복 PR | [`data.md`](./data.md) |
| 10 | **종단 상태 행을 삭제하지 않는다** | 같은 이슈를 다음 스캔에서 또 분석한다. LLM 비용 반복 | 〃 |

---

## 이슈 필터 — 규칙 기반 1차 배제

LLM 을 태우기 **전에** 거른다. 순서대로 판정하고, 하나라도 걸리면 즉시 배제한다.

| # | 조건 | 배제 이유 |
|---|---|---|
| 1 | 이미 종료(closed)됨 | 기여 대상이 아니다 |
| 2 | 활성 PR 이 이미 존재 | 남의 작업과 충돌한다. 중복 기여는 커뮤니티에서 환영받지 못한다 |
| 3 | 요구사항이 불명확 | 무엇을 만들지 모르는 채 코드를 쓰면 반드시 실패한다 |
| 4 | 대규모 아키텍처 변경 | 성공률이 낮고, 실패 시 소모하는 토큰이 크다 |

**필터 판정은 이유와 함께 저장한다.** 저장하지 않으면 매 스캔마다 같은 이슈를 다시 판정한다.

### 우선 탐색 라벨

```
good first issue · help wanted · bug · enhancement · documentation
```

라벨이 없는 이슈를 배제하지는 않는다. 우선순위일 뿐이다.

---

## 이슈 분석 산출물 (PRD §11)

LLM 출력은 **구조화해서 저장한다.** 원문을 정본으로 삼으면 도메인이 모델 출력 포맷에 묶인다.

```json
{
  "category": "enhancement",
  "difficulty": "MEDIUM",
  "implementationFeasible": true,
  "estimatedFiles": 4,
  "estimatedLoc": 120,
  "testRequired": true,
  "breakingChange": false,
  "confidence": 0.87
}
```

| 필드 | 판정에 쓰이는 방식 |
|---|---|
| `implementationFeasible=false` | → `REJECTED` |
| `breakingChange=true` | → `REJECTED` (Non-Goal — 대규모 변경은 대상이 아니다) |
| `confidence` | 사람에게 보여줄 추천 정렬 기준. **자동 선택 기준으로 쓰지 않는다** (불변식 2) |
| `difficulty` · `estimatedFiles` · `estimatedLoc` | 〃 |

**분석 컨텍스트에 저장소 전체를 넣지 않는다** (PRD §12). 키워드 → 코드 검색 → 관련 파일로 단계적으로 좁힌다.
넣으면 토큰 비용이 폭증하고, 컨텍스트가 희석돼 정확도도 떨어진다.

---

## 검증 파이프라인 (PRD §15)

**전부 샌드박스 안에서** 순서대로 수행한다. 앞 단계가 실패하면 뒤를 실행하지 않는다.

```
Compile ─▶ Unit Test ─▶ Integration Test ─▶ Format/Lint ─▶ Diff Inspection ─▶ AI Review
   │           │               │
   └───────────┴───────────────┴──▶ Error Analysis ──▶ (재시도)
```

| 단계 | 판정 | 실패 시 |
|---|---|---|
| Compile | 빌드 성공 여부 | 에러 분석 → `IMPLEMENTING` 회귀 |
| Unit / Integration Test | `RepositoryPolicy.test_command` 로 실행 | 〃 |
| Format / Lint | 대상 저장소의 포맷터 규칙 | 〃 |
| Diff Inspection | 의도 외 변경 혼입 검사 — 무관 파일 · 디버그 잔재 · 대량 포맷 노이즈 | 〃 |
| AI Review | LLM diff 리뷰 | `IMPLEMENTING` 회귀 |

**빌드 판정은 출력 문자열이 아니라 종료 코드로 한다.** 파이프로 자른 출력만 보고 성공 판정하면
파이프 종료 코드가 마지막 명령으로 덮여 실패를 통과로 읽는다.

---

## 재시도 전략 (PRD §17)

```
구현 ──▶ 테스트 ──PASS──▶ AI 리뷰 ──PASS──▶ READY_FOR_PR
 ▲         │                 │
 │       FAIL              FAIL
 │         ▼                 ▼
 └──── 에러 분석 ◀────────────┘
           │
           └── attempt < 3 ? ──NO──▶ FAILED ●
```

| 항목 | 값 |
|---|---|
| 상한 | `agent.execution.max-retries: 3` (`application.yml`) |
| 단계 타임아웃 | `agent.execution.timeout-seconds: 1800` (30분) |
| 상한 소진 | `FAILED` — **그 자체가 사람에게 넘기는 신호다** |

⚠️ **「3회」의 단위가 미정이다.** 단계별 독립 카운터인지 후보 전체 통합인지 정해지지 않았다.
`AgentRun.attempt` 의 의미가 여기서 갈리고, 비용 집계도 따라 흔들린다 → [`../rules/context/open-questions.md`](../rules/context/open-questions.md) **Q-6**.

**재시도마다 `agent_run` 과 `generated_change` 를 새 행으로 남긴다.** 덮어쓰면 무엇이 왜 바뀌었는지 추적이 사라진다.

---

## 대상 저장소 브랜치 규칙 (PRD §14)

```
oss-agent/issue-{issueNumber}-{short-description}
```

우리 저장소의 브랜치 컨벤션([`../rules/conventions/git-workflow.md`](../rules/conventions/git-workflow.md))과 **다르다.** 섞지 않는다.
대상 저장소에 나가는 커밋 메시지도 마찬가지로 그쪽 `CONTRIBUTING.md` 를 따른다 — S-5.

## 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-18 | smileboy0014 | 초안 생성 — PRD v1.1 §9~§17 기준 · 불변식 10개 신설 |
