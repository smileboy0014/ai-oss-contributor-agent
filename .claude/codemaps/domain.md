# 도메인 코드맵

> 기준 — [PRD](../../docs/ai-oss-contributor-agent-prd.md) §9 Issue Discovery · §10 Candidate State Machine · §11 Issue Analysis · §15 Verification Pipeline · §17 Retry Strategy (v1.2 Draft).
> 비즈니스 규칙과 상태머신. **구현 전에 이 맵을 확인하고 일치시킬 것.**
> ✅ **상태머신·필터·검증·재시도 루프·승인 게이트 셋이 전부 구현됐다**(#12 · #9 · #19 · #21 · #24 · #18 · #23).
> 2026-09-28 기준 이 문서의 전이 표는 `CandidateStatus` 와 일치한다. 남은 것은 **`spring-kafka` End-to-End 실측**이다.

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
| — | `DISCOVERED` | **배제되지 않은** 이슈로 후보 생성 | 스캐너 |
| `DISCOVERED` | `ANALYZING` | 분석 배치가 집어 든다 — `AnalyzeIssuesUseCase`(#11). 트리거는 스캔 파이프라인의 마지막 단계(#14). ⚠️ **`POST /candidates/{id}/analyze` 는 만들지 않는다** — PRD v1.2 가 §23 에서 지웠다(#30). 분석은 스캔 파이프라인의 한 단계이지 사람이 거는 호출이 아니다 | 시스템 |
| `ANALYZING` | `ANALYZED` | LLM 분석 산출물 저장 | 시스템 |
| `ANALYZING` | `FAILED` ● | **분석 실패 — 즉시 종단** (Q-6: `ANALYZE`·`PLAN` 은 재시도 없음) | 시스템 |
| `ANALYZED` | `REJECTED` ● | `implementation_feasible=false` · `breaking_change=true` · **`confidence < agent.analysis.min-confidence`**(#11) | 시스템 |
| `ANALYZED` | `SELECTED` | **사람이 고른다** — `POST /candidates/{id}/select` | **사람** |
| `SELECTED` | `REJECTED` ● | **사람이 선택을 취소한다** — `POST /candidates/{id}/reject`. 🔴 `cancelSelection` 이 출발 상태를 **직접** 본다 — 전이표에는 `ANALYZED → REJECTED` 도 있어서 맡겨 두면 `rejectAsInfeasible`(시스템 판정)과 같은 것이 된다 | **사람** |
| `SELECTED` | `IMPLEMENTING` | 구현 요청 (`POST /candidates/{id}/implement`, #18). 🔴 계획·컨텍스트·clone 은 **이 전이 앞**에서 돈다 — 그 구간의 레이트리밋은 503 + `Retry-After` 이고 후보는 `SELECTED` 그대로다(지연 ≠ 실패) | **사람이 트리거** |
| `SELECTED` | `FAILED` ● | **구현 계획을 세우지 못했다** — `agent.plan.max-attempts` 소진 (#16). `REJECTED` 가 아니다: 그쪽은 사람의 선택 취소다 | 시스템 |
| `IMPLEMENTING` | `TESTING` | 코드 생성 완료 | 시스템 |
| `TESTING` | `REVIEWING` | 빌드·테스트 통과 | 시스템 |
| `TESTING` | `IMPLEMENTING` | 테스트 실패 → 에러 분석 후 재시도 | 시스템 |
| `REVIEWING` | `READY_FOR_PR` | AI 리뷰 통과 | 시스템 |
| `REVIEWING` | `IMPLEMENTING` | 리뷰 실패 → 재시도 | 시스템 |
| `IMPLEMENTING`·`TESTING`·`REVIEWING` | `FAILED` ● | **재시도 상한 소진** · 복구 불가 오류(계획 밖 경로 · diff 에 시크릿 패턴 #96 · 판정 불가) | 시스템 |
| `IMPLEMENTING`·`TESTING`·`REVIEWING` | `SELECTED` | 🔴 **일시 장애로 미룬다** (#98) — 이미지 없음 · 데몬 다운 · LLM 5xx · clone 끊김. 후보의 코드와 무관하고 준비되면 같은 요청이 성공하므로 **태우지 않는다**. `attempt` 는 0 으로, `selectedAt` 은 그대로(사람이 골랐다는 사실은 변하지 않는다). 웹은 503 + `Retry-After`. **사람이 `implement` 를 다시 누른다** — 자동 재시도가 아니라 게이트다 | 시스템 |
| `READY_FOR_PR` | `PR_CREATED` ● | PR 생성 요청 (`POST /candidates/{id}/pull-request`, #23) → 정책 재확인 → Fork 동기화 → **upstream 재clone + 저장 diff 적용 → Fork push**(S-1) → **draft** PR(S-2). push 는 게이트 **뒤**에서만 일어난다 | **사람이 트리거** |

● = **종단 상태**. `PR_CREATED` · `REJECTED` · `FAILED` 셋이다.

### 사람이 눌러야만 넘어가는 지점 셋 — Q-5 확정 (2026-09-25 · #24)

S-6 이 요구하는 승인 지점이다. **스케줄러·워커가 이 선을 넘지 않는다.**

| 게이트 | 엔드포인트 | 구현 | 넘으면 |
|---|---|---|---|
| 선정 | `POST /candidates/{id}/select` | ✅ #24 | 자동 선정 — 제품 정의 붕괴 |
| 착수 | `POST /candidates/{id}/implement` | ✅ #18 (루프는 #21) | 비용이 통제 없이 나간다 (LLM · 샌드박스 30분) |
| **PR 생성** | `POST /candidates/{id}/pull-request` | ✅ #23 (push 배선 2026-09-28) | **검증 안 된 코드가 메인테이너 큐로** — S-2 |

⚠️ **`implement` 가 PR 까지 흘려보내지 않는다.** PRD §24 시퀀스가 `implement` 한 번으로
Draft PR 까지 그려 두었던 것이 결함이었고, **PRD v1.2 에서 게이트 셋으로 정정됐다**(#30).

선택 취소(`SELECTED → REJECTED`)도 **사람 행위로만** 일어난다. 자동 취소 경로를 만들지 않는다.
구현된 엔드포인트는 `POST /candidates/{id}/reject` 다 — 🔴 `DELETE` 가 아니다.
지우는 것이 아니라 **종단 상태로 전이시키는 행위**이고, 후보 행은 그대로 남아
「골랐다가 물렸다」는 기록이 된다.

### 셋이 다 열렸다 — 그러나 「열렸다」와 「끝까지 통한다」는 다르다 (2026-09-28 개정)

#24 는 셋 중 **하나만** 열었고, 나머지 둘을 미룬 것은 **지금 열면 후보가 빠져나올 수 없는 상태에
갇히기 때문**이었다. #18 과 #23 이 각각 실행기·생성기와 **같은 PR 에서** 열어 그 조건을 없앴다.

| 엔드포인트 | 열면 어땠나 | 어떻게 해소했나 |
|---|---|---|
| `implement` | `IMPLEMENTING` 에서 **나갈 트리거가 없다** | ✅ #18 이 코딩·검증 실행기와 함께, #21 이 루프·상한 소진을 붙여 열었다 |
| `pull-request` | PR 을 만들 코드가 없으니 **PR 없이 종단 `PR_CREATED`** | ✅ #23 이 PR 생성기와 함께 열었다 — ① 대외 호출이 **성공한 뒤에만** 쓰기 트랜잭션 ② `markPrCreated` 가 **`PullRequest` 를 인자로 요구** |

🔴 **그런데 `pull-request` 는 열린 채 항상 실패했다.** #22 가 만든 `ForkPublisher.publish` 를
아무도 부르지 않아 `GeneratedChange.commitSha` 가 영영 NULL 이었고, `CreateDraftPrUseCase` 는
그것을 「push 한 기록이 없다」로 읽어 예외로 끝났다. PLAN-22 는 「배선은 #23」, PLAN-23 은
「#22 가 push 를 끝낸다」로 **서로에게 넘긴 채 머지**됐다. 2026-09-28 에 게이트 뒤에서
동기화 → 재clone + diff 적용 → push → sha 기록 → PR 순서로 배선했다.
**「엔드포인트가 409 를 낸다」는 문이 열렸다는 증거지, 문 뒤에 길이 있다는 증거가 아니다.**

`CandidateApprovalApiTest` 는 세 게이트의 **거부 상태 코드**(403·409)를 고정한다. 404 회귀는
문이 열리며 지웠다 — 열린 문에 404 를 계속 요구하면 회귀가 아니라 거짓말이 된다.

### 승인 게이트를 구조로 고정한 것 — #24

단위 테스트는 **부른 코드**를 보지만, S-6 이 막으려는 것은 **누군가 나중에 부르게 되는 것**이다.
`ApprovalGateArchitectureTest`(ArchUnit)가 넷을 고정한다.

| 규칙 | 막는 것 |
|---|---|
| **web 어댑터만** `SelectCandidateUseCase`·`CreateDraftPrUseCase` 를 부른다 (허용목록) | 자동 진입점이 게이트를 부르는 것.<br>⚠️ 거부목록(`..scheduler..` 열거)이었다가 뒤집었다 — 진짜 자동 실행자는 그 패키지에 없다 |
| `selectByHuman`·`cancelSelection`·**`markPrCreated`** 를 승인 경로 밖에서 부르지 못한다 | UseCase 를 **우회해 엔티티를 직접** 전이시키는 것. 위 규칙은 타입을 지목하므로 이것을 못 잡는다 |
| `selectedAt` 은 `selectByHuman` 에서만 대입 · **`PullRequest.draftFor` 는 `CandidatePrWriter` 만 부른다** | 승인의 **증거**가 다른 데서 만들어지는 것. PR 생성의 증거는 필드가 아니라 **행의 존재**라 생성 경로를 묶는다 (#23) |
| `PolicyClearance` 가 `..adapter.in..` 에 없음 | 외부가 통행증을 주입하는 것 (S-5) |
| `candidate` → `repository.adapter` 금지 · 남의 UseCase 는 `application` 에서만 | 규율 ①④ |

⚠️ 첫 규칙은 **지금 위반 0건이다** — `ScanScheduler`(#14)가 유일한 스케줄러이고 스캔만
기동한다. 위반이 없으면 규칙이 잘못 쓰여 있어도 초록이라, 미끼(`AutoSelectProbe`)를 두고
**같은 규칙이 그것을 무는지**를 함께 단언한다. 규칙이 실제로 필요해지는 시점은
스케줄러가 후보를 건드리기 시작하는 때다.

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
| 6 | **`RepositoryPolicy` 없이 구현 단계로 못 간다** — `startImplementing` 이 `PolicyClearance` 를 **인자로 요구**한다(#24). 확인 없이 부르는 것이 컴파일되지 않고, `null` 은 런타임 가드가 막는다 | 규약 위반 PR 은 읽히지 않고 닫힌다 | S-5 |
| 7 | **`ai_contribution_allowed` 판정 실패는 「보류」다** — 푸는 길은 `POST /repositories/{id}/policy/resolution` 하나이고 **사람만 부른다**(#24) | AI 기여를 금지한 저장소에 PR 을 연다 | S-5 · Q-8 |
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

## 이슈 필터 — 규칙 기반 1차 배제 (#9)

LLM 을 태우기 **전에** 거른다. **대외 호출을 하나도 하지 않는다** — 여기서 GitHub 을 더
부르면 「싸게 거른다」는 목적이 무너진다. 입력은 이미 저장된 `issue` 행뿐이다.

### 판정은 3상태다

| 판정 | 뜻 | 후보가 되나 |
|---|---|---|
| `REJECTED` | 배제 확정 | ❌ |
| `UNDECIDED` | **규칙으로 가를 수 없다** — LLM 이 본다 (#11) | ✅ |
| `PASSED` | 어떤 규칙에도 걸리지 않았다 | ✅ |
| `NULL` | 아직 판정하지 않았다 | — |

🔴 2상태로 두면 **규칙이 가를 수 없는 것을 「통과」로 뭉개게 된다.** 이 제품의 품질 축은
「좋은 코드를 쓰는가」가 아니라 「나쁜 결과를 걸러내는가」라, 판정을 흐리는 것은 기능이
아니라 훼손이다.

### 집계

```
REJECTED 가 하나라도 있으면  → REJECTED
아니고 UNDECIDED 가 있으면   → UNDECIDED
전부 통과면                  → PASSED     ← 도달 가능해야 한다
```

🔴 **`PASSED` 가 도달 불가능해지면 안 된다.** 입력과 무관하게 늘 `UNDECIDED` 를 내는
규칙을 하나라도 넣으면 하류(#11)가 `UNDECIDED` 를 통과로 취급할 수밖에 없고,
뭉개기가 한 층 위로 옮겨질 뿐이다.

**모든 규칙을 평가하고 사유를 전부 모은다.** 첫 매치에서 끊으면 사유 분포가
**규칙 순서의 함수**가 되어 「분포를 보고 규칙을 고친다」가 불가능해진다.

### 규칙

| # | 조건 | 판정 | 상태 |
|---|---|---|---|
| 1 | 이미 종료(closed)됨 | `REJECTED` | ⚠️ 구현됐으나 **발화하지 않는다** — 위 「알려진 공백」. #14 |
| 2 | 활성 PR 이 이미 존재 | — | ❌ **규칙 아님** — 아래 |
| 3 | 본문이 없다 | `REJECTED` | ✅ |
| 3 | 본문이 짧다 · 코멘트가 많다 | `UNDECIDED` | ✅ |
| 4 | `breaking`·`epic`·`rfc`·`design` 라벨 | `REJECTED` | ✅ |

⚠️ 규칙 3에서 「짧다」와 「논의가 길다」를 **배제로 쓰지 않는다.** 스택트레이스 링크
한 줄짜리 명확한 버그 리포트가 있고, 긴 논의가 활발한 논의일 수도 있다. 상관을 배제
근거로 쓰면 명확한 이슈를 대량으로 잃는다.

⚠️ 규칙 4는 **라벨만** 본다. 「refactor」가 본문에 있다고 대규모 변경이 아니다 —
「이건 리팩토링이 아니라 버그입니다」에도 걸린다. 라벨은 메인테이너가 붙인 명시적 신호다.

**판정은 사유와 함께 저장한다.** 사유는 `FilterReason` enum 의 이름이고 **자유 텍스트가
아니다** — 본문 발췌를 넣으면 대상 저장소 텍스트가 우리 DB 를 거쳐 LLM 프롬프트·PR 본문
으로 흘러간다 (S-4).

### 🔴 「활성 PR 존재」는 여기 없다 — S-2 방어의 이전

확인하려면 Search API(30 req/min) 또는 이슈당 Timeline 1호출이다. 수천 건 전량에 그
비용을 치르면 아끼려던 것보다 더 쓴다. 후보가 처음으로 극소수가 되는 지점은
**필터를 통과해 LLM 분석에 들어가기 직전(#11 입구)** 이다.

> **#11 입구에서 1회 + #23 에서 재확인 1회.** 재확인이 필요하다는 것은
> 「#23 에도 둔다」는 뜻이지 「#11 에 두지 않는다」는 뜻이 아니다.

🔴 이것은 **방어의 이전이지 소멸이 아니다.** 「활성 PR 이 있는 이슈에 Draft PR 을 하나
더」는 S-2 가 막으려는 바로 그 행위이고(메인테이너 리뷰 큐 오염 · OSS 에서 스팸 취급),
#9 가 그 유일한 예정 지점이었다. 옮겨간 자리에서 **권고가 아니라 차단 게이트**여야 한다.

### 우선 탐색 라벨

```
good first issue · help wanted · bug · enhancement · documentation
```

라벨이 없는 이슈를 **배제하지 않는다.** 대부분의 이슈에 이 라벨이 없어 배제로 쓰면
Phase 1 대상(`spring-kafka` 단일)이 거의 다 떨어진다. 점수는 `issue.filter_priority` 에
**영속**된다 — 반환값으로만 두면 #11 이 분석 순서를 SQL 로 정렬할 수 없다.
가장 높은 라벨 하나를 쓰고 **합산하지 않는다** (합산하면 라벨을 많이 붙이는 저장소가
구조적으로 앞선다).

### ⚠️ 알려진 공백 — 부르는 곳이 없다

스캔이 끝난 뒤 자동으로 이어지지 않는다. 트리거(스케줄러·스캔 후속)는 **#14** 의 몫이다.
같은 저장소를 스캔과 필터가 동시에 도는 경합도 #14 가 막아야 한다 — 지금은 스케줄러가
없어 동시 실행 경로 자체가 없다.

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

**전부 샌드박스 안에서** 순서대로 수행한다. 앞 단계가 실패하면 뒤를 실행하지 않는다 — `SandboxChangeVerifier`(#19).

```
COMPILE ─▶ TEST ─▶ DIFF ─▶ (AI Review — 검증 밖, REVIEW 단계)
   │        │       │
   └────────┴───────┴──▶ RetryPolicy ──▶ Retry(IMPLEMENTING 회귀) · Stop(FAILED)
```

| 단계 (`VerificationStage`) | 판정 | 실패 시 |
|---|---|---|
| `COMPILE` | `RepositoryPolicy.build_command` 종료 코드 | `Retry` → `IMPLEMENTING` 회귀 |
| `TEST` | `RepositoryPolicy.test_command` 종료 코드. 규약에 명령이 없으면 **`UNDETERMINED`** | `Retry` / `UNDETERMINED` 면 `Stop` |
| `DIFF` | 의도 외 변경 혼입 검사 — 계획 밖 파일 · 디버그 잔재 · 대량 포맷 노이즈. 출력이 `sandbox.max-output-chars` 에서 잘리면 **`UNDETERMINED`**. 🔴 `git add -A` → `git diff --cached` → `git reset` 순서다(#100) — 스테이징하지 않으면 **새 파일이 검사에서 빠진다** | 〃 |

⚠️ 워밍·씨딩(Q-4)은 검증 안이 아니라 **코딩 전** `ChangeVerifier.prepare` 에서 원본 clone 으로 돈다(#99).
검증 안에서 처음 워밍하면 생성 코드가 `testClasses` 컴파일에 섞여, 컴파일 실패가 종료코드(재시도 대상)가
아니라 예외(종단)로 나와 3바퀴 루프가 첫 바퀴에서 끝났다.
| AI Review (`REVIEW`) | LLM diff 리뷰 — 판정 셋(`PASS`·`CHANGES_REQUESTED`·`UNDETERMINED`) (#20) | `CHANGES_REQUESTED` → 회귀 · `UNDETERMINED` → `Stop` |

⚠️ PRD §15 의 「Unit → Integration → Format/Lint」 다섯 칸은 **셋으로 줄였다**(glossary 「Verification」).
포맷 명령은 `RepositoryPolicy` 에 필드가 없어 하드코딩하면 대상 저장소 규약을 우리 어휘로 대체하는
것이고(S-5), 통합 테스트는 `testCommand` 가 하나뿐인데다 실행 단계가 `network=none` 이라 정상 코드가
실패한다(Q-4). **이름만 있는 칸을 두지 않는다.**

**빌드 판정은 출력 문자열이 아니라 종료 코드로 한다.** 파이프로 자른 출력만 보고 성공 판정하면
파이프 종료 코드가 마지막 명령으로 덮여 실패를 통과로 읽는다.
**결과는 그 바퀴의 `generated_change.test_result`·`review_result` 에 남는다** — 2026-09-28 까지는
값 타입만 있고 컬럼에 앉히는 코드가 없어 PR 본문의 검증 절이 늘 비어 있었다.

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
| 상한 | `agent.execution.max-attempts: 3` (`application.yml`) |
| **바퀴** 타임아웃 | `agent.execution.timeout-seconds: 1800` (30분) — `CODE→VERIFY→REVIEW` 한 바퀴의 벽시계 상한. 단계마다 확인하고 넘었으면 다음 단계로 가지 않고 `Stop`(재시도 아님 — 같은 입력에 같은 시간이 든다). ⚠️ 2026-09-28 까지는 값만 검증되고 **아무도 읽지 않는 키**였다. 단계 안은 `agent.llm.timeout`·`SANDBOX_TIMEOUT_SECONDS` 가 각자 막는다 |
| 상한 소진 | `FAILED` — **그 자체가 사람에게 넘기는 신호다** |

✅ **「3회」의 단위는 `CODE → VERIFY → REVIEW` 한 바퀴다** — Q-6 확정 (2026-09-25).
리뷰 실패는 테스트 실패와 **합산**이고, `ANALYZE`·`PLAN` 은 카운터 밖(실패 시 즉시 `FAILED`)이다.
판정 필드는 **`contribution_candidate.attempt`** 이고 도메인이 `MAX_ALLOWED_ATTEMPTS = 3` 을
넘는 값을 거부한다 — 상한을 올리려면 도메인 코드를 고쳐야 하고 그것이 리뷰에 보인다(불변식 ⑧).

✅ **키 이름이 `agent.execution.max-attempts` 로 맞춰졌다** (2026-09-27 · #21).
값은 **총 시도 수**이고 이제 이름이 그것을 말한다 — 이전 이름(`max-retries`)은 1 만큼
다른 개념이었다. 🔴 **이름만 바꿨다** — 값(3)도 비교도 그대로다. 「retries 니 한 번 더」로
읽고 비교를 옮기면 Q-6 의 곱셈 예산이 무효가 된다.

⚠️ **전송 계층 축 둘은 `max-retries` 인 채로 남는다**(`github.max-retries` ·
`agent.llm.max-retries`) — 그쪽은 이름과 의미가 맞다. 셋을 같은 이름으로 맞추지 않는다.

**재시도마다 `agent_run` 과 `generated_change` 를 새 행으로 남긴다.** 덮어쓰면 무엇이 왜 바뀌었는지 추적이 사라진다.

### ✅ 실행체가 생겼다 — `ImplementCandidateUseCase` 의 루프 (2026-09-27 · #21)

#18 이 1바퀴만 돌렸고 루프·상한 소진 판정·`REVIEW` 배선·후보 단위 실패 기록을 #21 이 닫았다.

| 무엇 | 어디 |
|---|---|
| 루프 | `ImplementCandidateUseCase.runOutsideTransaction` — 🔴 **트랜잭션 밖**(최악 3 × 30분) |
| 「재시도가 의미 있는가」 | `RetryPolicy` — 순수 판정 |
| 「더 돌 수 있는가」 | 🔴 `ContributionCandidate.retryImplementation` — **카운터의 주인이 후보 루트다** |
| 전이·기록 | `CandidateRetryWriter` — 짧은 트랜잭션 (self-invocation 회피) |

🔴 **판정 둘을 한곳에 두지 않는다.** 「의미 있는가」와 「더 돌 수 있는가」를 합치면
도메인 상수가 두 군데가 되고 한쪽만 고쳐지는 날이 온다.

#### 🔴 재시도는 화이트리스트다

「무엇이 재시도 **불가**인가」를 열거하면 **새 실패 종류가 조용히 재시도로 떨어진다** —
대가는 LLM 과금 ×3 과 샌드박스 90분이다. 그래서 **재시도 가능한 것만** 열거한다.

| 신호 | 판정 |
|---|---|
| `report.passed()` && `review.passed()` | `Proceed` → `READY_FOR_PR` |
| 검증이 `FAILED` (판정 불가 없음) · 리뷰가 `CHANGES_REQUESTED` | `Retry` |
| 🔴 `UNDETERMINED`(검증·리뷰) · 예외 **전부** | `Stop` → `FAILED` |

⚠️ 통과 판정을 `!failed` 로 쓰지 않는다 — `UNDETERMINED` 가 조용히 접힌다.

#### 🔴 루프의 성공 종착은 `READY_FOR_PR` 이다 — S-2

`PR_CREATED` 로 가지 않는다. 그 다음은 **세 번째 승인 게이트**(#23)다.
`ApprovalGateArchitectureTest` 가 고정한다 — 「`markPrCreated` 를 부르는 타입은
`CandidatePrWriter` 하나」(허용목록 · #23 이 열며 0 개에서 하나로 바뀌었다) 와
「그 UseCase(`CreateDraftPrUseCase`)를 부르는 것은 web 어댑터뿐」. 미끼(`AutoPrCreateProbe`)를
두어 규칙이 실제로 무는지 함께 단언한다.

#### 실패 사유가 DB 에 남는다 (S-4)

`CODE`·`VERIFY`·`REVIEW` 세 단계 전부 `AgentRun` 실패 행을 남긴다.
⚠️ **상한 소진에 행을 하나 더 만들지 않는다** — 마지막 바퀴의 실패 행이 이미 있고,
또 남기면 같은 실패가 두 번 세어져 비용 집계가 어긋난다.
상한 소진은 행이 아니라 **후보 상태(`FAILED`)와 `attempt` 값**이 말한다.

#### 🕳 닫지 못한 것

| | |
|---|---|
| **같은 실패 2회 조기 중단이 발화하지 않을 수 있다** | 지문 재료가 빌드 출력이라 타임스탬프·경로가 섞이면 같은 오류라도 갈린다. 실측 전에는 정규화하지 않는다(거부목록이 된다). 물지 못해도 **상한이 뒤를 받친다** |
| 🔴 **Q-4 — 네트워크를 요구하는 테스트가 3바퀴를 태운다** | 정상 코드가 `FAILED` 로 떨어진다. #21 이 그 비용을 **1회에서 3회로 증폭**시켰다. 고칠 주체는 Q-4 다 |
| **루프 전체 상한이 없다** | 바퀴별 벽시계 상한(`agent.execution.timeout-seconds`)만 있다. 최악 90분. 실행 프로필 분리(Q-3)와 함께 본다 |
| **바퀴마다 통행증을 다시 받지 않는다** | 루프가 도는 동안 대상 저장소가 AI 기여 금지로 바뀌어도 루프 안에서는 막는 것이 없다. ✅ **PR 게이트가 재확인한다**(`CreateDraftPrUseCase` → `assertContributionAllowed`, #23) — 나가는 문에서 걸리므로 S-5 는 지켜진다. 루프가 태우는 비용은 막지 못한다 |
| 🔴 **Q-4 종결 조건 — `spring-kafka` 실측 미완** | 워밍 → 씨딩 → 오프라인 실행 → push → PR 을 실 대상으로 한 번도 통과시키지 않았다. push 가 배선되기 전에는 원리적으로 불가능했다 |

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
경계가 다르므로 **후보 루트가 자기 상태로** 들고 있다 — `contribution_candidate.attempt`(V4 · #12),
절대 상한은 도메인 상수 `MAX_ALLOWED_ATTEMPTS = 3`. `retryImplementation` 이 판정한다(#21).

## 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-18 | smileboy0014 | 초안 생성 — PRD v1.1 §9~§17 기준 · 불변식 10개 신설 |
| 2026-09-27 | smileboy0014 | PRD v1.2 반영 — `POST /candidates/{id}/analyze` 는 **만들지 않는다**로 확정(§23 에서 삭제) · §24 참조 갱신 (#30) |
| 2026-09-28 | gt.park | 코드와 재대조 — 착수 게이트 ✅(#18) · `SELECTED → FAILED` 행 추가(#16) · 검증 5단계 → 3단계 · `markPrCreated` 허용목록 · 바퀴 타임아웃 배선 · **Fork push 미배선 발견·배선** · E2E 실측 미완 명시 |
