# 도메인 코드맵

> 기준 — [PRD](../../docs/ai-oss-contributor-agent-prd.md) §9 Issue Discovery · §10 Candidate State Machine · §11 Issue Analysis · §15 Verification Pipeline · §17 Retry Strategy (v1.1 Draft).
> 비즈니스 규칙과 상태머신. **구현 전에 이 맵을 확인하고 일치시킬 것.**
> ⚠️ **상태머신은 구현됐다**(#12) — 전이 규칙·불변식 ①②⑧·재시도 상한이 `candidate/domain` 에 있다.
> **필터·검증 파이프라인은 여전히 미구현**이고, 전이를 부르는 UseCase·엔드포인트도 아직 없다(#13 · #24).

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
                       ┌─────────────┐   분석 실패
                       │  ANALYZING  │──────────────────▶ (FAILED ●)
                       └──────┬──────┘   Q-6
                              │ LLM 판정 완료
                              ▼
                       ┌─────────────┐   feasible=false
                       │  ANALYZED   │──────────────────▶┌────────────┐
                       └──────┬──────┘   breaking=true   │ REJECTED ● │
                              │                          └────────────┘
                              │ ★사람이 고른다★ POST /select
                              ▼
                       ┌─────────────┐   ★사람이 취소★
                       │  SELECTED   │──────────────────▶ (REJECTED ●)
                       └──────┬──────┘   POST /reject
                              │ POST /implement
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
| `ANALYZING` | `FAILED` ● | **분석 실패 — 즉시 종단** (Q-6: `ANALYZE`·`PLAN` 은 재시도 없음) | 시스템 |
| `ANALYZED` | `REJECTED` ● | `implementation_feasible=false` 또는 `breaking_change=true` | 시스템 |
| `ANALYZED` | `SELECTED` | **사람이 고른다** — `POST /candidates/{id}/select` | **사람** |
| `SELECTED` | `REJECTED` ● | **사람이 선택을 취소한다** — `POST /candidates/{id}/reject` | **사람** |
| `SELECTED` | `IMPLEMENTING` | 구현 요청 (`POST /candidates/{id}/implement`) | **사람이 트리거** |
| `IMPLEMENTING` | `TESTING` | 코드 생성 완료 | 시스템 |
| `TESTING` | `REVIEWING` | 빌드·테스트 통과 | 시스템 |
| `TESTING` | `IMPLEMENTING` | 테스트 실패 → 에러 분석 후 재시도 | 시스템 |
| `REVIEWING` | `READY_FOR_PR` | AI 리뷰 통과 | 시스템 |
| `REVIEWING` | `IMPLEMENTING` | 리뷰 실패 → 재시도 | 시스템 |
| `IMPLEMENTING`·`TESTING`·`REVIEWING` | `FAILED` ● | **재시도 상한 소진** | 시스템 |
| `READY_FOR_PR` | `PR_CREATED` ● | PR 생성 요청 (`POST /candidates/{id}/pull-request`) → Fork push + **draft** PR | **사람이 트리거** |

● = **종단 상태**. `PR_CREATED` · `REJECTED` · `FAILED` 셋이다.

### 사람이 눌러야만 넘어가는 지점 셋 — Q-5 확정 (2026-09-25 · #24)

S-6 이 요구하는 승인 지점이다. **스케줄러·워커가 이 선을 넘지 않는다.**

| 게이트 | 엔드포인트 | 넘으면 |
|---|---|---|
| 선정 | `POST /candidates/{id}/select` | 자동 선정 — 제품 정의 붕괴 |
| 착수 | `POST /candidates/{id}/implement` | 비용이 통제 없이 나간다 (LLM · 샌드박스 30분) |
| **PR 생성** | `POST /candidates/{id}/pull-request` | **검증 안 된 코드가 메인테이너 큐로** — S-2 |

⚠️ **`implement` 가 PR 까지 흘려보내지 않는다.** PRD §24 시퀀스는 `implement` 한 번으로
Draft PR 까지 그렸는데, 그대로 구현하면 위 세 번째 게이트가 사라진다 — PRD 결함이다(#30).

선택 취소(`SELECTED → REJECTED`)도 **사람 행위로만** 일어난다. 자동 취소 경로를 만들지 않는다.

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
| 8 | **재시도 상한을 무한으로 바꾸지 않는다** — 판정 필드는 `contribution_candidate.attempt`, 절대 상한은 도메인 상수 | LLM 비용이 조용히 폭주하고 `FAILED` 신호가 사라진다 | S-6 |
| 9 | **후보는 이슈당 1건** | 같은 작업 이중 실행 · 중복 PR | [`data.md`](./data.md) |
| 10 | **종단 상태 행을 삭제하지 않는다** | 같은 이슈를 다음 스캔에서 또 분석한다. LLM 비용 반복 | 〃 |

---

## 이슈 증분 수집 (#8)

매 스캔 전량 조회는 레이트리밋을 태운다. 커서와 조건부 요청으로 줄인다.

| 수단 | 값 | 어디에 |
|---|---|---|
| **커서** | 마지막으로 본 `updated_at` | `oss_repository.issue_cursor_updated_at` |
| **조건부 요청** | page 1 응답의 `ETag` → `If-None-Match` | `oss_repository.issue_cursor_etag` |

### 🔴 틀리면 조용한 규칙 넷

| # | 규칙 | 어기면 |
|---|---|---|
| 1 | **ETag 는 `since`·`page` 둘 다와 짝이다.** 커서가 전진하면 버리고, 다음 페이지로 승계하지 않는다 | 우연히 매치되어 **304 를 받으면 그 페이지를 통째로 건너뛴다** |
| 2 | **커서 전진은 저장이 끝난 뒤에만** | 저장 실패 시 그 구간을 영영 다시 읽지 않는다 |
| 3 | **경계는 포함(inclusive)** — `since = max(updatedAt)` | 배타로 잡으면 같은 초에 갱신된 경계 이슈가 **영구 누락**된다 (GitHub `updated_at` 은 초 단위) |
| 4 | **조회는 `updated_at` 오름차순** — `IssueSource` 계약 | 내림차순이면 앞 N 페이지가 「가장 최근」이라, 커서를 전진시키는 순간 **안 읽은 오래된 이슈를 영구히 건너뛴다** |

⚠️ 4번은 **페이크로 잡히지 않는다** — 페이크는 넣어 준 순서를 돌려줄 뿐이다.
어댑터가 보내는 쿼리 파라미터를 고정하는 테스트가 유일한 방어다.

### 레이트리밋은 실패가 아니라 지연이다

| 상황 | 처리 |
|---|---|
| 남은 호출 < `github.rate-limit-threshold` | 어댑터가 **호출 전에** `GitHubRateLimitException` |
| 1차 리밋 | `ScanResult.delayedUntil = resetAt` — **정상 종료** |
| 2차 리밋 | `Retry-After` 그대로. 없으면 기본 5분 |
| 권한 오류(403, 리밋 신호 없음) | **실패** — 예외가 밖으로 |

🔴 **`Thread.sleep` 으로 기다리지 않는다.** 1차 리밋 리셋은 최대 1시간이고,
단일 프로세스(Q-3)에서 스레드를 그만큼 잡으면 코딩 작업(최대 30분)과 겹쳐 죽는다.

🔴 **부분 수집은 버리지 않는다.** 리밋에 걸리기 전까지 읽은 페이지는 저장하고 커서를
거기까지 전진시킨다 — 버리면 같은 구간을 다시 읽어 리밋을 또 태운다.

### 재수집 시 필터 결과

`github_updated_at` 이 **전진했으면** 필터 결과를 무효화하고, 아니면 보존한다.
매번 덮으면 판정이 스캔마다 날아가고, 무조건 보존하면 본문이 바뀐 이슈에 낡은 판정이 남는다.
기준은 **GitHub 이 준 신호**다 — 우리가 내용을 비교해 추측하지 않는다.

### ⚠️ 알려진 공백 — `state` 는 영원히 `open` 이다

조회가 `state=open` 고정이라 수집되는 것은 전부 open 이다. **닫힌 이슈를 우리가 조회하지
않으므로 한 번 저장된 이슈는 영원히 open 으로 남는다.**
아래 필터 1번(「이미 종료됨」)이 **이 컬럼을 믿으면 안 된다** — #9·#14 의 몫이다.

### S-6 — 수집은 후보를 만들지 않는다

스캔은 스케줄러가 주기적으로 도는 **자동 경로의 첫 단계**다. 여기서 `ContributionCandidate`
를 만들면 「사람이 고른다」가 무너진다. `Issue` 행만 만든다.

---

## 이슈 필터 — 규칙 기반 1차 배제

LLM 을 태우기 **전에** 거른다. 순서대로 판정하고, 하나라도 걸리면 즉시 배제한다.

| # | 조건 | 배제 이유 |
|---|---|---|
| 1 | 이미 종료(closed)됨 | 기여 대상이 아니다.<br>⚠️ `issue.state` 를 믿으면 안 된다 — 위 「알려진 공백」 |
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

✅ **「3회」의 단위는 `CODE → VERIFY → REVIEW` 한 바퀴다** — Q-6 확정 (2026-09-25).
리뷰 실패는 테스트 실패와 **합산**이고, `ANALYZE`·`PLAN` 은 카운터 밖(실패 시 즉시 `FAILED`)이다.
판정 필드는 **`contribution_candidate.attempt`** 이고 도메인이 `MAX_ALLOWED_ATTEMPTS = 3` 을
넘는 값을 거부한다 — 상한을 올리려면 도메인 코드를 고쳐야 하고 그것이 리뷰에 보인다(불변식 ⑧).

⚠️ `application.yml` 의 키 이름은 `agent.execution.max-retries` 인데 **의미는 attempts**(총 시도 수)다.
1 만큼 다른 개념이라 「off-by-one 버그」로 오인해 고치면 Q-6 의 곱셈 예산이 무효가 된다.
개명은 그 프로퍼티를 실제로 읽는 #21 에서 한다.

**재시도마다 `agent_run` 과 `generated_change` 를 새 행으로 남긴다.** 덮어쓰면 무엇이 왜 바뀌었는지 추적이 사라진다.

---

## 대상 저장소 브랜치 규칙 (PRD §14)

```
oss-agent/issue-{issueNumber}-{short-description}
```

우리 저장소의 브랜치 컨벤션([`../rules/conventions/git-workflow.md`](../rules/conventions/git-workflow.md))과 **다르다.** 섞지 않는다.
대상 저장소에 나가는 커밋 메시지도 마찬가지로 그쪽 `CONTRIBUTING.md` 를 따른다 — S-5.

## 애그리거트 경계

| 애그리거트 | 루트 | 멤버 | 왜 이 경계인가 |
|---|---|---|---|
| 저장소 | `OssRepository` | `RepositoryPolicy` | 1:1 · 규약 없이 저장소만 두는 의미가 없다 |
| 이슈 | `Issue` | — | 저장소당 수천 개. 저장소 애그리거트에 넣을 수 없다 |
| 후보 | `ContributionCandidate` | `PullRequest` | 불변식 ①③⑨ 가 후보 상태와 **함께 서야 한다** |
| 실행 기록 | `AgentRun` | — | append-only · 재시도마다 증가 |
| 생성 변경분 | `GeneratedChange` | — | append-only · 행마다 수십 KB |

애그리거트를 넘는 참조는 **ID 값**이다. 넘지 않으면 JPA 연관관계를 쓴다 —
[`../rules/conventions/architecture.md`](../rules/conventions/architecture.md) 규율 ④.

⚠️ 불변식 ⑧(재시도 상한)은 `AgentRun` 컬렉션을 세어 판정하지 않는다.
경계가 다르므로 **후보 루트가 자기 상태로** 들고 있어야 한다. 형태는 Q-6 확정 후(#21).

## 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-18 | smileboy0014 | 초안 생성 — PRD v1.1 §9~§17 기준 · 불변식 10개 신설 |
