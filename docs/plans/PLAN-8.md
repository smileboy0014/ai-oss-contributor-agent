# PLAN-8: 이슈 증분 수집 — 커서 · ETag · 레이트리밋 대응

**이슈**: [#8](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/8)
**type**: feature
**작성일**: 2026-09-25
**작성자**: smileboy0014

---

## 1. 요구사항

### 배경

#6 이 `IssueSource` 를 만들어 **조회 1회**를 올바로 수행한다(403 구분 · ETag · `Link` 판독).
이 작업은 그 위에 **여러 번의 조회를 이어 붙이는 것** — 커서를 들고, 페이지를 넘기고,
결과를 멱등하게 저장하고, 리밋에 부딪히면 **실패가 아니라 지연**한다.

매 스캔 전량 조회는 레이트리밋을 태운다. 인증 5,000 req/h 이고 Search API 는 별도 30 req/min 이라
**스캐너가 가장 먼저 부딪힌다** — `external-deps.md` 가 못 박은 그 지점이다.

### 기능 요구사항 (FR)

| # | 요구 | 근거 |
|---|---|---|
| FR-1 | `updated_at` 커서 + `ETag` 조건부 요청으로 증분 수집 | 완료 조건 1 |
| FR-2 | `issue` 테이블에 **멱등하게** 저장 — 재실행이 중복 행을 만들지 않는다 | 완료 조건 2 |
| FR-3 | `X-RateLimit-Remaining` **임계 미만이면 선제 지연** (소진 후가 아니다) | 완료 조건 3 |
| FR-4 | 2차 레이트리밋(403) 백오프 | 완료 조건 4 |
| FR-5 | 페이크로 통합 테스트 | 완료 조건 5 |
| FR-6 | 커서·ETag 를 **영속**한다 (프로세스 재시작을 넘어 이어진다) | FR-1 의 전제 |

### 비기능 요구사항 (NFR)

| # | 항목 | 기준 |
|---|---|---|
| NFR-1 | 🔴 트랜잭션 안에 대외 호출 없음 | 페이지마다 「조회(밖) → 짧은 트랜잭션 저장」 |
| NFR-2 | 레이트리밋 예산 | 스캔 1회의 페이지 상한. 상한 없이 `hasNext` 를 따라가면 첫 스캔이 리밋을 태운다 |
| NFR-3 | 블로킹 금지 | 리밋 대기를 `Thread.sleep` 으로 하지 않는다 — §3.3 |

---

## 2. 게이트 판정

### 안전 경계

⚠️ 이슈 본문은 「해당 없음 (읽기 전용)」이라 적었다. **읽기 전용인 것은 맞지만 둘이 닿는다.**

| 조항 | 접촉 | 어떻게 지키는가 |
|---|---|---|
| **S-4** 시크릿 | ✅ 약하게 | 대상 저장소 **이슈 본문을 DB 에 적재**한다. 저장소가 시크릿을 이슈에 적어 뒀을 수 있다. `Issue.body` 의 `@ExternalText(TARGET_REPOSITORY)` 로 #28 스크럽 대상에 잡힌다.<br>⚠️ **로그 인젝션 금지** — 이슈 제목·본문을 **포맷 문자열로 쓰지 않는다**(`logging.md`). 식별자(`repo`·`#number`)만 찍는다.<br>⚠️ **수용 위험 명시** — #28 이전까지 **본문 원문이 DB 에 그대로 들어간다.** `logging.md` 는 「DB 에 넣을 때도 스크럽 동일 적용」이라 했으므로, 이번엔 **마커만 달고 미루는 것**이다 |
| **S-6** 승인 지점 | ✅ **구조적** | 이 UseCase 는 **스케줄러가 주기적으로 도는 자동 경로의 첫 단계**다. 여기서 `ContributionCandidate` 를 만들면 「사람이 고른다」가 무너진다. **`Issue` 행만 만들고 후보를 만들지 않는다** — `candidate` 도메인을 import 하지 않는 것이 그 준수다 |
| **S-2** | ✅ 구조적 | 읽기만 한다. `IssueSource` 에 쓰기 동사가 없어 코멘트·라벨 부여가 불가능하다 |
| S-1 · S-3 · S-5 | — | push·샌드박스·기여 규약을 건드리지 않는다 |

### 미결 대조

| 항목 | 걸리는가 | 처리 |
|---|---|---|
| **Q-3** 프로필 분리 | ✅ 인접 | **닫지 않는다.** 이 PR 은 **UseCase 까지** — 스케줄러·API 트리거는 **#14**. NFR-3 의 근거가 Q-3 이다 |
| **Q-6** 재시도 단위 | — | 해당 없음. 전송 재시도는 #6 의 `github.max-retries`, 파이프라인 카운터와 무관. **어느 쪽 카운터도 만들지 않는다** |
| **Q-9** 대역 | — | 닫혀 있다. `FakeIssueSource` 를 **확장해서** 쓴다(§4-G3) |
| Q-2 마이그레이션 | ✅ | **V6**. H2·PostgreSQL 공통 문법만 |

**닫는 미결 없음.**

---

## 3. 설계 — 판단이 갈리는 곳

### 3.1 커서를 어디에 두나 — `oss_repository` 에 2컬럼 (V6)

커서는 **저장소당 1개**이고 스캔을 넘어 살아남아야 한다(FR-6).

| 컬럼 | 뜻 |
|---|---|
| `issue_cursor_updated_at` | 다음 조회의 `since` — **데이터 워터마크** |
| `issue_cursor_etag` | 조건부 요청용 ETag (**page 1 응답의 것**) |

⚠️ **`last_scanned_at` 을 재사용하지 않는다.** 「언제 돌렸나」와 「어디까지 봤나」는 다르다.
겹쳐 쓰면 스캔이 실패해도 커서가 전진해 **이슈를 영구히 건너뛴다.**

#### 기각 — `SELECT MAX(github_updated_at)` 으로 파생하기

컬럼 없이 `issue` 테이블에서 파생할 수 있고, 「저장된 데이터를 넘어 전진할 수 없다」는
성질이 공짜로 따라온다. 매력적이지만 **기각한다.**

**커서가 행 보존 정책에 묶인다.** 오래된 이슈를 정리·아카이브하는 순간 `MAX` 가 **뒤로 점프**해
전량 재스캔이 터진다 — 커서를 지우지도 않았는데 리밋을 통째로 태우는 사고가 난다.
명시적 커서는 그 결합이 없고, 디버깅할 때 값을 눈으로 볼 수 있다.

### 3.2 🔴 ETag 의 짝은 **둘**이다 — `since` 와 `page`

가장 틀리기 쉬운 지점이다. ETag 는 **URL 단위**로 유효한데 `since` 와 `page` 가 **둘 다** URL 에 들어간다.

```
1회차: GET /issues?since=T0&page=1        → 200, ETag=E0
2회차: GET /issues?since=T1&page=1  + If-None-Match: E0   ← ❌ E0 는 T0 용
       GET /issues?since=T0&page=2  + If-None-Match: E0   ← ❌ E0 는 page=1 용
```

**규정 둘.**
1. **커서를 전진시키면 ETag 를 버린다**(`null`). 304 를 받았을 때만 둘 다 유지한다.
2. **`IssueQuery.nextPage()` 는 ETag 를 승계하지 않는다.** 지금 코드는 승계한다 — **고친다.**
   커서에 저장하는 ETag 는 **page 1 응답의 것**으로 규정한다.

⚠️ **「틀려도 조용하다」는 반만 맞다.** 잘못된 ETag 가 매치되지 않으면 200 을 받아 손해는 성능뿐이지만,
**우연히 매치되어 304 를 받으면 그 페이지를 통째로 건너뛴다.** 조용한 데이터 손실이다.

### 3.3 🔴 「지연」은 슬립이 아니다 — 중단하고 커서를 남긴다

| 해석 | 판정 |
|---|---|
| `Thread.sleep(resetAt - now)` | ❌ 1차 리밋 리셋은 **최대 1시간**. 단일 프로세스(Q-3)에서 스레드를 1시간 잡으면 코딩 작업(최대 30분)과 겹쳐 죽는다 |
| **정상 종료 + 커서 보존** | ✅ **채택.** 다음 스캔이 이어받는다 |

`ScanResult.delayedUntil` 로 호출자(#14 스케줄러)에게 넘긴다. **이 UseCase 는 언제 다시 부를지 정하지 않는다.**

⚠️ **부분 수집은 버리지 않는다.** 3페이지를 읽고 리밋에 걸렸으면 그 3페이지를 저장하고 커서를 거기까지 전진시킨다.

#### 🔴 그 전진이 안전한 **유일한 이유**는 오름차순 정렬이다

어댑터가 `sort=updated&direction=asc` 를 보낸다(`GitHubIssueSource:51-52`). 그래서 앞 N 페이지가
**가장 오래된 것들**이고, 그 최대값으로 커서를 전진시켜도 **안 읽은 구간이 뒤에 남는다.**

⚠️ **GitHub 기본값은 `desc` 이고, 이 보장이 `IssueSource` 계약 어디에도 적혀 있지 않다.**
누가 정렬을 바꾸면 앞 N 페이지가 「가장 최근」이 되고, 커서를 거기로 전진시키는 순간
**안 읽은 오래된 이슈 전부를 영구히 건너뛴다.** `FakeIssueSource` 는 넣어 준 순서를 돌려줄 뿐이라
**페이크 테스트로는 절대 잡히지 않는다.**

→ **`IssueSource` javadoc 에 오름차순을 계약으로 명문화하고, 어댑터 테스트로 고정한다.**

#### 커서 경계는 **포함**(inclusive)이다

GitHub `updated_at` 은 초 단위라 같은 초에 갱신된 이슈가 여럿일 수 있다.
다음 `since` 를 `max + 1s`(배타)로 잡으면 **경계 초의 미수집 이슈가 영구 누락**된다.

**`since = max`(포함)로 잡는다.** 경계 이슈가 매번 1건 재수집되지만 FR-2 의 멱등 upsert 가 흡수한다.
페이지네이션 도중 순서가 변동해 한 건이 미끄러져도 **다음 스캔이 경계부터 다시 읽어 회수한다** —
배타로 잡으면 회수되지 않는다.

### 3.4 🔴 임계 감지는 **어댑터**가 한다 (2026-09-25 결정)

이슈 완료 조건 3은 **「임계 미만이면」 = 소진 전 선제 지연**이다. 그런데 `IssuePage` 는
레이트리밋을 **일부러 싣지 않는다** — #6 이 「남은 호출 수는 GitHub 의 개념이고 도메인에 뜻이
없다」(규율 ①)고 결정했다. 그래서 UseCase 는 임계를 볼 수 없다.

**`IssuePage` 계약을 바꾸지 않고, 어댑터가 책임진다.** 레이트리밋이 GitHub 의 개념이라는
#6 의 논거를 그대로 따르면 **판정도 GitHub 어댑터의 일**이다.

`GitHubApiClient.warnIfRateLimitNear` 가 지금 경고만 찍는 자리에서, 임계 미만이면
`GitHubRateLimitException(PRIMARY, resetAt)` 을 **던진다.** UseCase 는 이미 그 예외를
「지연」으로 처리하므로 **분기가 늘지 않는다.**

⚠️ **파급 — 모든 GitHub 어댑터에 적용된다.** #7(규약 수집)도 임계 근처에서 같은 예외를 받는다.
그쪽은 「부분 성공」이 의미 없으므로 **보류(S-5)로 떨어뜨리는 것이 맞다** — 해당 세션에 알렸다.

⚠️ 기존 `github.rate-limit-threshold: 100` 을 **그대로 쓴다.** `IssueScanProperties` 에 임계를
새로 만들지 않는다 — 중복 설정은 두 값이 갈라지는 순간 진실이 둘이 된다.

### 3.5 2차 레이트리밋 백오프

`Retry-After` 가 오면 **그 값을 그대로** 쓴다. 자체 지수 백오프를 계산하지 않는다 —
2차 리밋은 「너무 빨리 보냈다」는 신호라 우리 추측으로 짧게 잡으면 차단이 길어진다.
`GitHubRateLimitException.earliestRetryAt(now)` 가 이미 「신호 없으면 null → 호출자가 기본 지연」
까지 계약으로 적어 뒀으므로 **그 메서드를 쓴다**(scope 분기를 다시 짜지 않는다).

⚠️ **신호가 없을 때 회차별로 늘리지 않는다 — 한계를 명시한다.**
연속 충돌 횟수는 스캔을 넘어 살아남아야 하는데 그러려면 **컬럼이 하나 더 필요**하다.
`Retry-After` 없는 2차 리밋은 드물고, 그 드문 경우를 위해 스키마를 늘리는 것은 비싸다.
대신 **기본값을 보수적으로** 잡는다(`github.scan.secondary-limit-backoff`, **기본 5분**).
회차별 증가가 실제로 필요해지면 그때 컬럼을 추가한다.

---

## 4. 변경 파일

| # | 경로 | 레이어 | 신규/수정 | 내용 |
|---|------|--------|----------|------|
| 1 | `db/migration/V6__add_issue_scan_cursor.sql` | — | 신규 | `oss_repository` 커서 2컬럼 |
| 2 | `repository/domain/OssRepository.java` | domain | 수정 | 커서 필드 + `advanceIssueCursor` · `keepIssueCursor` |
| 3 | `issue/domain/Issue.java` | domain | 수정 | 팩토리 `fromSnapshot` + `updateFrom` |
| 4 | `issue/domain/IssueSource.java` | domain | **수정** | 🔴 **오름차순 정렬을 계약으로 명문화**(§3.3) |
| 5 | `issue/domain/IssueQuery.java` | domain | **수정** | 🔴 **`nextPage()` 가 ETag 를 승계하지 않게**(§3.2) |
| 6 | `support/github/GitHubApiClient.java` | adapter/out | **수정** | 🔴 **임계 미만이면 throw**(§3.4) |
| 7 | `issue/adapter/out/persistence/IssueJpaRepository.java` | adapter/out | 신규 | `findByRepositoryIdAndGithubIssueNumber` |
| 8 | `issue/application/ScanIssuesUseCase.java` | application | 신규 | 오케스트레이션 (`@Transactional` **안 붙인다**) |
| 9 | `issue/application/IssuePageWriter.java` | application | 신규 | 페이지 단위 **짧은 트랜잭션** (NFR-1) |
| 10 | `issue/application/ScanResult.java` | application | 신규 | 건수 · 커서 · `delayedUntil` · **`hasMore`** |
| 11 | `issue/application/IssueScanProperties.java` | application | 신규 | 페이지 상한 · 2차 백오프 (**임계는 없다** — §3.4) |
| 12 | `application.yml` | — | 수정 | `github.scan.*` |
| 13 | `src/test/.../issue/domain/FakeIssueSource.java` | test | **수정** | **호출 순서별 응답 주입**(G-3) — 지금은 `failWith` 하나로 모든 호출이 같게 실패한다 |
| 14 | 테스트 | test | 신규 | §6 |

### 체크리스트 답변

| # | 항목 | 답 |
|---|------|---|
| 1 | 소유 도메인 | **`issue`**. 커서 컬럼만 `repository` 애그리거트(저장소의 속성) |
| 2 | 레이어 배치 | domain(계약·엔티티) · application(오케스트레이션·트랜잭션) · adapter/out(영속·임계 판정) |
| 3 | 능력 인터페이스 | 신설 불필요 — `IssueSource` 를 소비. **계약 문서만 강화**(§3.3) |
| 4 | 🔴 트랜잭션 안 대외 호출 없음 | ✅ `ScanIssuesUseCase` 에 `@Transactional` 을 **안 붙인다.** 저장은 `IssuePageWriter` 의 짧은 트랜잭션. ⚠️ 자기 호출은 프록시를 안 타므로 **별도 빈** |
| 5 | 상태 전이 | 해당 없음. **후보를 만들지 않는다** — S-6 |
| 6 | 멱등성 | ✅ `uk_issue_repository_number`. `(repositoryId, number)` 조회 후 insert/update |
| 7 | `Clock` 주입 | ✅ 커서·`delayedUntil`. `Instant.now()` 금지 |
| 8 | 🔴 안전 경계 | §2 — S-4 · S-6 |

### `Issue.updateFrom` 의 규정 — 필터 결과를 어떻게 하나

`Issue` 에는 #9 가 채울 `filterResult`·`filterReason` 이 있다. 재수집 때 **덮으면** 매 스캔마다
판정이 날아가고(스키마 주석이 막으려던 바로 그것), **무조건 보존하면** 본문이 바뀐 이슈에
낡은 판정이 남는다.

**규정 — `github_updated_at` 이 전진했으면 필터 결과를 무효화한다.**
GitHub 이 「내용이 바뀌었다」고 알려 준 신호를 그대로 쓴다. 안 바뀌었으면 보존한다.

### 스냅샷에 없는 필드

| 필드 | 처리 |
|---|---|
| `Issue.url` | `IssueSnapshot` 에 없다. **좌표+번호로 파생**한다(`https://github.com/{owner}/{name}/issues/{n}`). 계약을 넓히지 않는다 |
| `Issue.state` | 쿼리가 `state=open` 고정이라 **수집되는 것은 전부 open** 이다. `"open"` 으로 채운다.<br>⚠️ **알려진 공백** — 닫힌 이슈를 우리가 조회하지 않으므로 **한 번 저장된 이슈는 영원히 open 으로 남는다.** #9 의 「종료됨」 필터가 이 값을 믿으면 안 된다. **이 PR 범위 밖이고, #9·#14 로 넘긴다** |
| `author` · `commentCount` | 엔티티에 자리가 없다. **버린다**(필터에 필요해지면 #9 가 컬럼을 추가) |

### 데이터 모델 (V6)

| 테이블 | 컬럼 | 타입 | 제약 |
|---|---|---|---|
| `oss_repository` | `issue_cursor_updated_at` | `TIMESTAMP(6) WITH TIME ZONE` | NULL 허용 |
| `oss_repository` | `issue_cursor_etag` | `VARCHAR(255)` | NULL 허용 |

#### 🔴 번호와 머지 순서 — 동시 작업 3건이 마이그레이션을 쌓는다

| 번호 | 주인 | 내용 |
|---|---|---|
| V4 | **#12** | `contribution_candidate` — `attempt` · `version` |
| V5 | **#7** | `repository_policy.pending_reason` 등 |
| **V6** | **#8 (이 작업)** | `oss_repository` 커서 2컬럼 |

⚠️ **번호만 피하면 부족하다 — 머지 순서도 V4 → V5 → V6 이어야 한다.**
`application.yml` 이 `out-of-order` 를 설정하지 않아 Flyway 기본값 `false`, `validate-on-migrate` 는 `true` 다.
V6 이 먼저 적용된 DB 에 나중에 V5 가 나타나면 **기동이 막힌다.**

⚠️ **H2 에서는 안 드러난다** — 매번 새로 떠서 V1 부터 쌓는다. **PostgreSQL 개발 DB 에서만 터진다**(Q-2b).

**이 PR 은 셋 중 가장 늦게 머지한다.**

---

## 5. 구현 순서 — sequential

| Stage | 내용 | 선행 |
|---|---|---|
| 1 | 계약 정정 — `IssueSource` 정렬 명문화 · `IssueQuery.nextPage()` ETag 제거 · `GitHubApiClient` 임계 throw | 없음 |
| 2 | V6 + `OssRepository` 커서 + `Issue` 팩토리/`updateFrom` | 1 |
| 3 | `IssueJpaRepository` + `IssuePageWriter` | 2 |
| 4 | `ScanIssuesUseCase` + `ScanResult` | 3 |
| 5 | `FakeIssueSource` 확장 + 테스트 | 4 |

**판정 근거**: Stage 1 이 계약이고 나머지가 그 위에 쌓인다. `contract-and-impl` 유형.

---

## 6. 테스트 계획

| # | 레벨 | 검증 내용 |
|---|------|----------|
| 1 | 유닛 | 커서 전진 시 **ETag 를 버린다** · 304 면 둘 다 유지 (§3.2-1) |
| 2 | 유닛 | 🔴 **`nextPage()` 가 ETag 를 승계하지 않는다** (§3.2-2) |
| 3 | **어댑터** | 🔴 **`sort=updated&direction=asc` 를 실제로 보낸다** — `MockRestServiceServer` 로 요청 URL 고정 (§3.3). **페이크로는 못 잡는 위험이다** |
| 4 | 유닛 | `Issue.fromSnapshot` · `updateFrom` — `github_updated_at` 전진 시 **필터 결과 무효화**, 아니면 보존 |
| 5 | 통합 | **멱등** — 같은 페이지 2회 수집해도 행이 안 는다 |
| 6 | 통합 | **PR 을 이슈로 저장하지 않는다**(`issuesOnly()`) |
| 7 | 통합 | 페이지네이션 — `hasNext` 추종 + **상한에서 멈추고 `hasMore=true`**(H-4) |
| 8 | 통합 | 🔴 **1차 리밋 → 실패가 아니라 지연**. 예외가 밖으로 안 나간다 |
| 9 | 통합 | 🔴 **부분 수집 보존** — 3페이지 읽고 리밋에 걸리면 저장되고 커서가 전진 |
| 10 | 통합 | 🔴 **2차 리밋 `Retry-After` 를 그대로** · 없으면 기본 5분 |
| 11 | 통합 | 권한 오류(403, 리밋 신호 없음)는 **지연이 아니라 실패** |
| 12 | 유닛 | 🔴 **임계 미만이면 어댑터가 던진다**(§3.4) — `GitHubApiClient` 단위 |
| 13 | 통합 | 커서 경계가 **포함**이라 경계 이슈가 재수집되고 멱등이 흡수한다 |

**대역** — `FakeIssueSource`(확장) · HTTP 층은 `MockRestServiceServer` · DB 는 Testcontainers.
실제 GitHub 를 타지 않는다.

⚠️ **3번이 이 계획에서 가장 중요한 테스트다.** 정렬이 §3.3 전체의 전제인데
페이크로는 절대 잡히지 않는다 — 어댑터 레벨에서만 고정된다.

---

## 7. 리스크

| # | 리스크 | 대응 |
|---|---|---|
| 1 | 정렬이 `desc` 로 바뀌어 **안 읽은 이슈를 영구히 건너뛴다** | 계약 명문화 + 어댑터 테스트 3 |
| 2 | ETag 가 `page` 와 어긋나 **304 로 페이지를 건너뛴다** | `nextPage()` 수정 + 테스트 2 |
| 3 | 커서를 실패 시에도 전진 | 전진은 **저장이 끝난 뒤에만**. 테스트 9 |
| 4 | 첫 스캔이 리밋을 태운다 | 페이지 상한 + `hasMore` |
| 5 | `@Transactional` 자기 호출이 프록시를 안 탄다 | `IssuePageWriter` **별도 빈** |
| 6 | 이슈 본문 시크릿이 로그로 | 식별자만 · **포맷 문자열로 쓰지 않는다** |
| 7 | **임계 throw 가 #7 을 놀라게 한다** | 해당 세션에 사전 통지 완료 |

---

## 8. 문서 동기화 대상

| 대상 | 필요 | 이유 |
|---|---|---|
| `codemaps/data.md` | ✅ | `oss_repository` 컬럼 2개 |
| `codemaps/domain.md` | ✅ | 증분 수집 규칙(커서·ETag 짝·정렬 전제) |
| `codemaps/architecture.md` | 판정 보류 | `issue` 에 application·adapter/out 이 처음 생긴다 — Phase 4 에서 확인 |
| `rules/context/glossary.md` | ✅ | 「증분 커서」·「조건부 요청」 |
| `rules/context/external-deps.md` | ✅ | **임계 미만 throw** 는 GitHub 어댑터의 새 규율이다 |
| `.env.example` | — | 새 환경변수 없음 |

---

## 9. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-25 | smileboy0014 | 초안 |
| 2026-09-25 | smileboy0014 | 마이그레이션 번호 V4 → **V6** (동시 작업 조율) + 머지 순서 제약 |
| 2026-09-25 | smileboy0014 | **격리 검토 반영** — ① FR-3(임계 선제 지연)이 현 계약으로 **구현 불가**임이 드러나 **어댑터가 던지는 방식**으로 확정(§3.4) ② ETag 의 짝이 `since` 말고 **`page` 도** 있다 — `nextPage()` 수정 ③ §3.3 의 전제인 **오름차순 정렬이 계약에 없다** — 명문화 + 어댑터 테스트 ④ 커서 경계 **포함** 확정 ⑤ `MAX(github_updated_at)` 파생 대안 기각 근거(행 보존 정책 결합) ⑥ `updateFrom` 의 필터 결과 규정 ⑦ `state`·`url` 소스 부재 명시 + **닫힌 이슈 공백을 #9 로 이관** ⑧ `ScanResult.hasMore` ⑨ `FakeIssueSource` 확장 ⑩ S-6 구조적 준수 · S-4 로그 인젝션·수용 위험 추가 ⑪ 2차 백오프 회차 증가를 **한계로 명시**하고 기본값을 5분으로 |
