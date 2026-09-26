# PLAN-13: Candidate 조회 API 구현

**이슈**: [#13](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/13)
**type**: feature
**작성일**: 2026-09-25
**작성자**: smileboy0014

> **rev 2** — 격리 검토(`gap-analyzer`) 반영. 🔴 1 · 🟡 7 · 🔵 5 + 추가 2. 요약은 §10.

## 1. 요구사항

### 배경

`GET /api/candidates` 가 **빈 배열 고정**이다(`CandidateController:14`). `contribution_candidate` 를
읽는 코드가 이 저장소에 하나도 없다 — Spring Data 인터페이스조차 없다.

#12 가 상태머신을 깔았지만 **그 상태를 볼 수단이 없다.** S-6 의 승인 지점(`select`·`implement`·
`pull-request`, #24)은 「사람이 후보를 보고 고른다」를 전제하는데, 보는 쪽이 비어 있으면
그 전제가 성립하지 않는다. 이 이슈는 **#24 의 입력**을 만든다.

### ⚠️ 선행 #11 이 열려 있다 — 테이블은 비어 있다

`#11`(이슈 분석 → 후보 생성)이 후보를 **넣는** 쪽인데 아직 미구현이다. 따라서 이 PR 이
끝나도 **운영상 조회 결과는 계속 빈 배열**이다.

그래도 지금 만드는 것이 맞다고 본다 — API 계약과 필터·페이지네이션 규약을 #11 보다 먼저
고정해야 #11 이 어떤 모양으로 데이터를 넣을지 알 수 있고, #24 가 그 위에 올라탄다.
**검증은 픽스처를 직접 적재해서 한다**(§6).

### 기능 요구사항 (FR)

| # | 요구사항 | 근거 |
|---|---------|------|
| FR-1 | `GET /api/candidates` — 목록 | 이슈 · PRD §23 |
| FR-2 | 목록 필터 — **상태 · 난이도 · 최소 신뢰도** | 이슈 완료조건 |
| FR-3 | 목록 페이지네이션 | 〃 |
| FR-4 | `GET /api/candidates/{id}` — 상세 (분석 결과 · `AgentRun` 이력 · 생성 변경분 요약) | 〃 |
| FR-5 | `adapter/in/web` 은 **변환·위임만**, 로직은 UseCase | 〃 · architecture §2 |
| FR-6 | 응답 DTO 를 `adapter/in/web/dto` 에 분리 | 〃 |
| FR-7 | 🔴 **diff 전문·프롬프트 원문을 싣지 않는다** — 크기와 해시만 | 〃 · S-4 |
| FR-8 | 🔴 **`error_message`·`analysis` 를 스크럽해서 노출** | 〃 · S-4 |
| FR-9 | 없는 후보는 404 | PRD §23 의 상세 조회 관행 |
| **FR-10** | 🔴 **목록에 기본 정렬을 계약으로 고정** — `ORDER BY id DESC` | 🆕 rev 2 — 아래 |

#### 🔴 FR-10 — 정렬 없는 페이지네이션은 페이지네이션이 아니다

rev 1 은 `page`·`size` 만 두고 정렬을 정하지 않았다. **`ORDER BY` 없는 `LIMIT/OFFSET` 은 행 순서를
보장하지 않는다** — page 0 과 page 1 에 같은 행이 나오거나 어떤 행이 어느 페이지에도 안 나올 수 있다.
H2 와 PostgreSQL 이 다르게 동작할 수 있어 `SchemaMigrationTest` 로도 안 잡힌다.

**`ORDER BY c.id DESC`** 로 고정한다. PK 라 인덱스가 이미 받치고, **V7 마이그레이션이 필요 없어**
§2 의 「체인 밖」 이점을 지킨다. `updatedAt DESC` 가 더 자연스럽지만 인덱스가 없다(§7 리스크 6).

⚠️ **이 PR 에 삭제 API 는 없다.** `PLAN-12.md:155` 가 불변식 ⑩(종단 행 삭제 금지)을 「삭제 API 가
생기는 #13 이 지킨다」로 넘겼는데 **그 서술이 틀렸다** — 이슈 #13 의 대상은 `GET` 둘뿐이다.
삭제 경로는 이 저장소 어디에도 없으므로 ⑩ 은 **여전히 자동 성립**한다. PLAN-12 를 같은 PR 에서 정정한다.

**FR-2 의 「저장소 필터」는 이 PR 에서 빼고 후속으로 돌린다** — §2 「범위 축소」 참조.

### 비기능 요구사항 (NFR)

| # | 항목 | 기준 |
|---|------|------|
| NFR-1 | 목록 응답에 **TEXT 컬럼을 로드하지 않는다** | `analysis`·`diff` 는 수십 KB. 목록 N건이면 N배다 |
| NFR-2 | 트랜잭션 | `@Transactional(readOnly = true)` — 조회 전용 |
| NFR-3 | 레이트리밋·타임아웃·LLM 비용 | **해당 없음** — 대외 호출이 없다 |

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키는가 |
|---|---|---|
| S-1 원본 저장소 쓰기 | — | GitHub 호출이 없다. 읽기 API 만 만든다 |
| S-2 draft 고정 | — | PR 생성 경로를 만들지 않는다 |
| S-3 샌드박스 | — | 대상 저장소 코드를 실행하지 않는다 |
| **S-4 시크릿 유출** | 🔴 **본체** | 아래 |
| S-5 대상 저장소 규약 | — | 대상 저장소에 나가는 산출물이 없다 |
| S-6 승인 지점 | 🔵 인접 | **조회 전용 — 상태 전이 메서드를 부르지 않는다.** #12 가 만든 전이는 UseCase 에서 호출하지 않는다 |

#### 🔴 S-4 — 이 PR 의 위험은 전부 여기 있다

**응답 본문이 새 유출 경로**다. DB 에 담긴 외부 텍스트를 그대로 내보내면 토큰이 HTTP 로 나간다.
`@ExternalText` 마커가 붙은 필드가 정확히 그 대상이다.

| 필드 | 출처 | 이 PR 의 처리 |
|---|---|---|
| `ContributionCandidate.analysis` | `LLM_RESPONSE` | 상세에만 · **`TokenRedactor.redact()` 통과** |
| `AgentRun.errorMessage` | `EXCEPTION` | 상세에만 · **`TokenRedactor.redact()` 통과** |
| `GeneratedChange.diff` | `TARGET_REPOSITORY` | ❌ **본문 미노출** — 크기 + SHA-256 앞 12자 |
| `GeneratedChange.testResult` | `BUILD_OUTPUT` | ❌ **본문 미노출** — 존재 여부만 |
| `GeneratedChange.reviewResult` | `LLM_RESPONSE` | ❌ **본문 미노출** — 존재 여부만 |

⚠️ **예외 메시지가 가장 위험하다.** `AgentRun.errorMessage` 는 HTTP 클라이언트 예외를 담는데,
거기에 요청 URL 이 들어가고 URL 에 토큰이 붙어 있으면 그대로 나간다 — `logging.md` 가
「가장 흔한 사고」라고 적어 둔 그 경로다.

⚠️ **적재 시점 스크럽에 기대지 않는다** — 다만 rev 1 의 근거는 틀렸다.
`errorMessage` 는 **이미 적재 시점에 걸러진다**(`AgentRun.fail` → `TokenRedactor.redact`).
그래도 읽기 측에서 한 번 더 거른다 — 그 경로를 타지 않고 들어온 행(직접 SQL·마이그레이션·이전 버전)이
있을 수 있고 비용이 호출 한 줄이다.

> ✅ **닫혔다 (2026-09-26 · #11).** 적재 측 방어가 생겼다 — `IssueAnalysis` 가 생성 시점에,
> `ContributionCandidate.completeAnalysis` 가 마지막 그물로 스크럽한다. 아래 문단은 그때의 판단 기록이다.

🔴 **`analysis` 는 적재 측 방어가 아예 없다.** 엔티티에 setter 도 팩토리도 없어 채우는 것은 **#11** 이고,
#11 이 스크럽 없이 채울 가능성이 열려 있다. **여기가 유일한 방어다** — S-1 이 「없는 권한에 기대지
않는다」로 어설션을 필수화한 것과 같은 형태다.

🟡 **잔여 위험 — `TokenRedactor` 가 `analysis` 의 S-4 를 닫지 못한다.** 그것이 잡는 것은 토큰 패턴
5종 + `Authorization` 헤더뿐이고, 클래스 javadoc 이 스스로 「토큰 패턴 치환만 한다」고 밝힌다.
`analysis` 는 LLM 응답이고 모델은 대상 저장소 컨텍스트를 받는다 — **모델이 대상 저장소의
DB 비밀번호나 비표준 사내 토큰을 인용하면 패턴에 안 걸리고 나간다.**
막자는 것이 아니다(FR-4 가 분석 결과 노출을 요구한다). **1차 방어는 송신 전 프롬프트 스크럽과 #28** 이고,
읽기 측 redact 는 마지막 그물이다.

🔵 **`analysis` 는 불투명 문자열로 계약한다.** #11 이 구조화 JSON 을 넣기로 하면 구조화는 후속이다 —
지금 형태를 「JSON 이 박힌 문자열」로 굳히지 않으려면 그때 계약을 바꾼다.

🔵 목록 응답에는 위 필드를 **하나도 싣지 않는다** — 유출면이 상세 1건으로 좁아진다(NFR-1 과 같은 방향).

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리는가 | 처리 |
|---|---|---|
| Q-3 실행 프로필 분리 | — | 조회는 `web` 쪽이고 지금 단일 프로세스다. 분리 시점 판단을 앞당기지 않는다 |
| Q-4·Q-11 | — | 샌드박스·LLM 미접촉 |
| Q-5 (닫힘) | 🔵 | 확정된 `select`·`reject` 엔드포인트를 **만들지 않는다** — #24 소관. 조회만 |
| Q-6 (닫힘) | 🔵 | `attempt` 를 상세 응답에 노출한다. 의미는 확정돼 있다 |
| 나머지 | — | 미접촉 |

**가정 없음.** 추측으로 채운 미결이 없다.

### ⚠️ 범위 축소 — 「저장소 필터」를 뺀다

이슈 완료조건의 「목록 — **저장소**·상태·난이도·신뢰도 필터」 중 **저장소만** 빠진다.

**문제** — `contribution_candidate` 에 `repository_id` 가 없다. `issue.repository_id` 를 경유해야 하는데
`issue` 는 다른 애그리거트라 엔티티·Spring Data 인터페이스를 직접 import 할 수 없다(규율 ④).

**택하지 않은 대안 3개**

| 대안 | 기각 사유 |
|---|---|
| `issue` UseCase 로 `repositoryId → issueId 목록` 을 받아 `issueId IN (...)` | 🔴 **스케일이 안 맞는다.** `codemaps/architecture.md` 가 「이슈는 수천 개」라고 적어 둔 그대로다. IN 절에 수천 개가 들어가고, 후보는 그중 극소수다. **필터 대상이 아니라 그 모집단으로 쿼리를 만드는 꼴** |
| JPQL 로 `issue` 테이블 조인 | 🔴 **규율 ④ 가 막으려던 두 결합 중 하나를 그대로 만든다.** `architecture.md` 는 연관관계 금지(**컴파일** 결합)와 물리 FK 금지(**DB** 결합)를 명시적으로 분리하고, 후자의 이유를 「테이블을 다른 DB 로 옮기면 제약이 깨지고 삭제 순서가 DB 에 묶인다」로 든다. **SQL 조인은 컴파일 결합은 없지만 그 DB 결합을 정확히 만든다** — 「모양이 이상하다」가 아니다 |
| `contribution_candidate.repository_id` 비정규화 | **설계로는 이것이 맞다** — 애그리거트를 넘는 ID 참조는 이 저장소의 기존 패턴이다(`issue_id` 가 이미 그렇다). 그러나 아래 두 이유로 지금은 못 한다 |

**비정규화를 지금 하지 않는 이유**

1. 🔴 **마이그레이션 체인** — V4(#12, 머지) · V5(#7, PR #52) · V6(#8, PR #51)가 잡혀 있어 나는 **V7** 이다.
   Flyway `baseline-on-migrate: false` 라 **V7 은 V5·V6 뒤에 머지돼야** 하고, 그러면
   **#13 이 먼저 갈 수 있다는 이점이 사라진다.** 지금 #13 이 체인 밖이라 빨리 갈 수 있는 것이 장점이다
2. **채우는 쪽이 없다** — 그 컬럼을 넣는 것은 후보를 만드는 **#11**(선행, 열림)이다.
   지금 컬럼만 만들면 전 행이 NULL 이라 필터가 항상 빈 결과다

**후속 처리** — #11 과 함께 한다. `contribution_candidate` 에 `repository_id` 를 넣고
#11 이 후보 생성 시 채우며, 이 PR 의 `CandidateQuery` 에 조건 하나를 더하면 끝난다.
이슈에 남긴다.

🔵 **#8 세션은 「`IssueJpaRepository` 를 만들어도 좋다, 충돌은 흡수하겠다」고 했다.** 고맙지만
**파일 충돌이 기각 사유가 아니었다** — 위 1번(IN 절 스케일)이 본체다. 파일을 만들어도 설계가 안 맞는다.

## 3. 스코프

| 도메인 | 변경 | 소유 판정 근거 |
|---|---|---|
| `candidate` | 신규·수정 | 「이 이슈가 기여 가능한가 · 지금 어느 단계인가」를 보여주는 것이 이 도메인의 정의 |

**도메인 간 계약** — 해당 없음. `candidate` 안에서 닫힌다(저장소 필터를 뺀 결과이기도 하다).

## 4. 기술 설계

### 변경 파일

| # | 경로 | 레이어 | 신규/수정 | 내용 |
|---|------|--------|----------|------|
| 1 | `candidate/adapter/out/persistence/ContributionCandidateRepository.java` | adapter/out | 신규 | Spring Data + 목록 프로젝션 쿼리 |
| 2 | `candidate/adapter/out/persistence/GeneratedChangeRepository.java` | adapter/out | 신규 | 상세의 변경분 요약 |
| 3 | `candidate/application/CandidateQuery.java` | application | 신규 | 필터 조건 값 타입 |
| 3b | `candidate/application/CandidateSummaryView.java` | application | 신규 | **JPQL 이 생성하는 뷰** — Q-3 대비(설계 ①) |
| 3c | `candidate/domain/CandidateNotFoundException.java` | domain | 신규 | 404 (설계 ④) |
| 4 | `candidate/application/FindCandidatesUseCase.java` | application | 신규 | 목록·상세 조회 · **스크럽** · 트랜잭션 경계 |
| 5 | `candidate/adapter/in/web/CandidateController.java` | adapter/in | 수정 | 변환·위임만 |
| 6 | `candidate/adapter/in/web/dto/CandidateSummary.java` | dto | 수정 | 목록 항목 |
| 7 | `candidate/adapter/in/web/dto/CandidateDetail.java` | dto | 신규 | 상세 |
| 8 | `candidate/adapter/in/web/dto/PageResponse.java` | dto | 신규 | 페이지 응답 |
| 9 | `candidate/adapter/out/persistence/AgentRunRepository.java` | adapter/out | 수정 | `findByCandidateId` 추가 |
| 10~ | 테스트 3종 | test | 신규 | §6 |

### 설계 ① — 목록은 **프로젝션**으로 읽는다 (NFR-1)

엔티티를 통째로 로드하면 `analysis`(TEXT, 수십 KB)가 함께 딸려 온다. 목록 20건이면 20배다.

```java
@Query("""
    SELECT new com.ossagent.candidate.adapter.in.web.dto.CandidateSummary(
        c.id, c.issueId, c.status, c.difficulty, c.confidence, c.attempt, c.selectedAt)
    FROM ContributionCandidate c
    WHERE (:status IS NULL OR c.status = :status)
      AND (:difficulty IS NULL OR c.difficulty = :difficulty)
      AND (:minConfidence IS NULL OR c.confidence >= :minConfidence)
    """)
Page<CandidateSummary> findSummaries(...);
```

🔄 **rev 2 — JPQL 이 만드는 것은 `application` 의 뷰 record 다.** rev 1 은 `adapter/in/web/dto` 를
직접 생성하려 했다. 규율 위반은 아니지만(둘 다 adapter 층) **대안이 있었고, rev 1 의 이분법
「대안은 엔티티 로드뿐」이 틀렸다.**

```java
// candidate/application/CandidateSummaryView.java  ← JPQL 이 생성
// candidate/adapter/in/web/dto/CandidateSummary.java ← 컨트롤러가 한 줄 매핑
```

- NFR-1 그대로 — constructor expression 이 선택한 컬럼만 SELECT 한다
- 의존이 `adapter/out → application → domain` 으로 **안쪽만 향한다**
- 비용은 record 1개 + 매핑 한 줄

**왜 바꾸나 — Q-3 이다.** `adapter/out/persistence` 가 `adapter/in/web/dto` 를 import 하면
**영속 계층이 web 진입점 없이 존재할 수 없다.** `web`/`worker` 프로필을 가를 때 정확히 이
import 를 먼저 끊어야 한다 — 「나중에 떼어낼 수 있게 한다」의 반대 방향이다.
부수적으로 HTTP 응답 계약이 바뀔 때마다 JPQL 문자열을 고쳐야 하는 결합도 사라진다.

⚠️ **Hibernate 6 함정 2개를 Stage 1 에서 먼저 확인한다.**
① `(:param IS NULL OR ...)` 는 파라미터 타입 추론 실패로 `SemanticException` 이 날 수 있다(enum·`BigDecimal`).
   나면 `Specification` 으로 동적 조립하고 `Tuple`/인터페이스 프로젝션으로 NFR-1 을 유지한다
② `Page` + constructor expression 의 파생 count 쿼리는 버전 의존이다 — `countQuery` 를 **명시**한다

### 설계 ② — 페이지 응답은 **우리 타입**으로 내보낸다

`Page<T>` 를 그대로 반환하지 않는다. Spring 이 `PageImpl` 직렬화에 대해 경고하고
(JSON 구조가 버전에 따라 바뀐다), 응답 계약에 Spring 타입이 들어온다.

```java
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages)
```

### 설계 ③ — 🔴 스크럽은 **UseCase 에서** 한다

DTO 변환 지점이 유일한 유출 통로다. 컨트롤러는 변환·위임만 하므로(FR-5) 스크럽을 거기 두면
「로직이 adapter 에 있다」가 된다.

```java
TokenRedactor.redact(candidate.getAnalysis())
TokenRedactor.redact(run.getErrorMessage())
```

`GeneratedChange` 는 **본문을 아예 DTO 에 담지 않는다** — 스크럽보다 강한 방어다.

| 노출 | 값 |
|---|---|
| `diffSize` | `diff.length()` |
| `diffSha256` | SHA-256 **앞 12자** — 같은 diff 인지 식별하는 용도. 전체를 내보낼 이유가 없다 |
| `hasTestResult`·`hasReviewResult` | `boolean` |

⚠️ 해시를 위해 상세에서는 `diff` 를 **로드한다.** 아래 「최신 1건」 규칙 때문에 1건이다(목록은 아예 안 읽는다).

🔄 **rev 2 — 어느 `GeneratedChange` 인가.** rev 1 은 「1건」이라고만 썼는데 **재시도마다 새 행이 쌓인다**
(Q-6 상한 3이므로 최대 3행). **최신 1건**(`findFirstByCandidateIdOrderByCreatedAtDesc`)만 요약하고
`totalChanges` 로 건수를 함께 준다. `(candidate_id, created_at)` 인덱스가 이미 받친다(V2:155).

**대가** — 실패한 이전 시도의 diff 해시는 보이지 않는다. 전건을 읽으면 수십 KB × 3 이라
NFR-1 의 예외 근거가 무너진다. 이력이 필요하면 `AgentRun` 쪽에 남아 있다.

### 설계 ④ — 404

없는 id 는 `CandidateNotFoundException`(domain) → `support/web` 에서 404 매핑.
도메인 예외에 `@ResponseStatus` 를 달지 않는다 — 같은 예외를 스케줄러가 던질 때 의미가 없어진다(architecture §4).

### 체크리스트 답변

| # | 항목 | 답 |
|---|------|---|
| 1 | 소유 도메인 | `candidate` |
| 2 | 레이어 배치 | 결정 트리 Q2(Repository 필요) → **application**. 입출력은 Q4 → adapter/in |
| 3 | 능력 인터페이스 | **불필요** — 자기 도메인 Spring Data 직접 주입(완화 ②) |
| 4 | 🔴 트랜잭션 안 대외 호출 없음 | ✅ **대외 호출 자체가 없다.** DB 만 |
| 5 | 상태 전이 영향 | ✅ **없다 — 조회 전용.** #12 의 전이 메서드를 호출하지 않는다 |
| 6 | 멱등성 | 조회라 해당 없음 |
| 7 | `Clock` 주입 | **해당 없음** — 시각을 만들지 않고 읽어서 내보낸다 |
| 8 | 🔴 안전 경계 | §2 — **S-4 가 본체** |

### API 계약

```http
GET /api/candidates?status=ANALYZED&difficulty=EASY&minConfidence=0.7&page=0&size=20
GET /api/candidates/{id}
```

| | 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| 요청(목록) | `status` | `CandidateStatus` | ✗ | 미지정 시 전체 |
| | `difficulty` | `String` | ✗ | **자유 문자열** — DB 가 `VARCHAR` 에 CHECK 없음. 없는 값은 400 이 아니라 **200 + 빈 목록** |
| | `minConfidence` | `BigDecimal` | ✗ | 이상(≥) |
| | `page`·`size` | `int` | ✗ | 기본 0 · 20. `size` 상한 100 |
| 응답(목록) | `items[]` | `CandidateSummary` | | 아래 |
| 응답(상세) | + | `CandidateDetail` | | `analysis`(스크럽·불투명 문자열) · `runs[]` · `change`(최신 1건 요약) · `totalChanges` · `pullRequest` |

**정렬은 고정이다** — `ORDER BY id DESC`. `sort` 파라미터를 받지 않는다(FR-10).

🔴 **`CandidateSummary` 필드 타입 — nullable 을 primitive 로 받지 않는다.**

| 필드 | 타입 | 근거 |
|---|---|---|
| `id`·`issueId` | `Long` | |
| `status` | `CandidateStatus` | NOT NULL |
| `difficulty` | `String` | nullable |
| **`confidence`** | **`BigDecimal`** | 🔴 **nullable 이다**(V2:95 에 NOT NULL 없음). 기존 record 가 primitive `double` 인데, `discover()` 가 채우지 않아 **`DISCOVERED`·`ANALYZING` 후보는 전부 NULL** 이다. 그대로 두면 JPQL constructor expression 이 언박싱 NPE 를 내 **정상 데이터에서 목록이 500** 이 된다 |
| `attempt` | `Integer` | NOT NULL (V4) |
| `selectedAt` | `Instant` | nullable |

| 상태 | 조건 |
|---|---|
| 200 | 정상 |
| 400 | `status` 형식 오류 · `size > 100` |
| 404 | 없는 후보 |

⚠️ **에러 바디 형식이 갈린다.** `ApiExceptionHandler` 는 `ProblemDetail` 을 돌려주지만
`spring.mvc.problemdetails.enabled` 가 설정에 없어 `MethodArgumentTypeMismatchException`(잘못된 `status`)은
**Spring 기본 에러 바디**로 나간다. §6 테스트 7 은 **404(우리 포맷)만** 형식을 단언하고,
400 은 상태 코드만 본다 — 포맷 통일은 이 PR 범위 밖이다.

## 5. 구현 순서

### 실행 모드: sequential

**판정 근거** — `contract-and-impl` 유형. Stage 2 가 Stage 1 의 Repository 를, Stage 3 이 Stage 2 의
UseCase 를 import 한다. 파일 겹침은 없지만 import 의존이 한 방향으로 쌓인다.

| Stage | 내용 | 선행 | 파일 |
|-------|------|------|------|
| 1 | DTO 3종 + Repository 2종 + `AgentRunRepository` 확장 | 없음 | 1,2,6,7,8,9 |
| 2 | `CandidateQuery` + `FindCandidatesUseCase` (스크럽 포함) | Stage 1 | 3,4 |
| 3 | Controller + 404 매핑 | Stage 2 | 5 + `support/web` |
| 4 | 테스트 | Stage 3 | §6 |
| 5 | 문서 동기화 | Stage 4 | §9 |

## 6. 테스트 계획

| # | 레벨 | 대상 | 검증 |
|---|------|------|------|
| 1 | 유닛 | `FindCandidatesUseCase` | 🔴 **`analysis`·`errorMessage` 가 스크럽된다** — 토큰을 심어 마스킹 확인 (S-4) |
| 2 | 유닛 | 〃 | 🔴 **`diff` 본문이 DTO 에 없다** — 크기·해시만. 리플렉션 아닌 타입으로 보장(필드 자체가 없음) |
| 3 | 유닛 | 〃 | 없는 id → `CandidateNotFoundException` |
| 4 | 유닛 | `PageResponse` | `totalPages` 계산 경계(0건 · 나누어떨어짐 · 나머지) |
| 5 | 통합 | Repository | 필터 조합 — 상태·난이도·신뢰도 각각과 조합. **픽스처를 직접 적재**(#11 부재) |
| 6 | 통합 | 〃 | 🔴 **목록 프로젝션이 `analysis` 를 로드하지 않는다** — **`StatementInspector`** 로 실행 SQL 원문을 수집해 `analysis` 가 없음을 단언. 아래 |
| 7 | 통합 | Controller | `@AgentIntegrationTest` + MockMvc — 200·404 · JSON 에 `diff`·`testResult`·`reviewResult` 가 **없음** |
| **8** | 유닛 | **DTO 전수** | 🔴 **규칙 테스트** — 아래 |

**대외 호출 대체** — **해당 없음.** GitHub·LLM·샌드박스를 부르지 않는다. DB 는 통합 테스트에서
실제로 쓴다(H2 + Flyway). Testcontainers PostgreSQL 은 `SchemaMigrationTest` 가 이미 덮는다.

#### 🔴 테스트 8 — 인스턴스가 아니라 **규칙**을 검사한다

rev 1 의 테스트 1·2·7 은 **지금 있는 필드에 대한 인스턴스 검증**이라, 내일 누가 `reviewResult` 를
DTO 에 추가하면 아무것도 빨개지지 않는다. 계획이 스스로 최상위 위험으로 꼽은 것이 정확히 그것이다.

이 저장소에 **더 강한 선례가 있다** — `ExternalTextMarkerTest` 가 「고정 목록으로 검사하지 않는다,
대신 규칙을 검사한다」며 TEXT 컬럼 전수를 훑는다. 같은 방식으로:

- `candidate.adapter.in.web.dto` 의 **record 컴포넌트를 리플렉션으로 전수** 훑는다
- 엔티티에서 `@ExternalText` 가 붙은 필드명(`diff`·`testResult`·`reviewResult`)과 **같은 이름의
  컴포넌트가 존재하지 않음**을 단언 — 구조로 막는다
- `analysis` 컴포넌트는 **존재를 허용하되** UseCase 가 redact 를 거쳐 채우는 것을 토큰 심기로 확인

#### 🔴 테스트 6 — `StatementInspector` 를 쓴다 (statistics 아님)

Hibernate `Statistics` 에는 **컬럼 단위 정보가 없다.** 「엔티티 로드 0 → 따라서 analysis 도 안 읽었다」는
유도는 유효하지만 우회적이고, 나중에 누가 엔티티 로드를 섞으면 **실패 메시지가 원인을 말하지 않는다**
(`testing-philosophy.md` 원칙 3 위반).

`StatementInspector` 는 실행 SQL **원문**을 준다. 테스트 전용 프로퍼티로 수집기를 꽂고
SELECT 문에 `analysis` 가 없음을 단언한다. 실패하면 SQL 이 그대로 찍혀 원인이 즉시 보인다.
「SQL 문자열 단언은 구현 의존」이라는 반론이 있지만 **검사 대상이 생성된 SQL 자체**라
`ExternalTextMarkerTest` 가 리플렉션을 의도적 예외로 둔 것과 같다 — 테스트 javadoc 에 근거를 남긴다.

#### ⚠️ 픽스처 적재 수단 — `EntityManager.persist` 로는 못 만든다

rev 1 의 「픽스처를 직접 적재」는 제약을 모르고 쓴 문장이었다.

| 엔티티 | 생성 경로 | 결과 |
|---|---|---|
| `ContributionCandidate` | `discover()` 만. `analysis`·`confidence` 를 채울 공개 경로 **없음** | 통합 테스트는 **`@Sql` 스크립트**로 넣는다 |
| `GeneratedChange` | 팩토리도 setter 도 **전혀 없음** (`@NoArgsConstructor(PROTECTED)` + `@Getter`) | 〃 |
| `AgentRun` | `RecordAgentRunUseCase` 경유 | UseCase 로 만들거나 `@Sql` |

유닛 테스트(1·2)는 **Mockito 로 엔티티를 스텁**한다. `ReflectionTestUtils` 로 필드를 찌르는 것은
「구현 내부 필드에 의존」 금지에 걸린다.

## 7. 리스크

| # | 리스크 | 영향 | 대응 |
|---|--------|------|------|
| 1 | 🔴 스크럽 누락 — 새 필드를 상세에 추가하며 `TokenRedactor` 를 빠뜨린다 | 토큰이 HTTP 로 나간다. 회수 불가 | `@ExternalText` 마커를 근거로 테스트가 **필드 단위로** 확인. #28 이 오면 그쪽으로 승계 |
| 2 | Hibernate 6 의 `:param IS NULL` · `Page` + constructor expression 함정 | Stage 1 에서 컴파일·런타임 실패 | **Stage 1 에서 먼저 확인.** 막히면 `Specification` + 인터페이스 프로젝션으로 선회(NFR-1 유지) · `countQuery` 명시 |
| 3 | 저장소 필터 누락 | 이슈 완료조건 일부 미충족 | §2 에 근거(IN 절 스케일 · 마이그레이션 체인 · #11 이 채운다) · 이슈 코멘트 · **#11 과 함께** 후속 |
| 4 | `#11` 부재로 실데이터 검증 불가 | 「돌긴 하는데 맞는지 모른다」 | 픽스처 적재로 필터·페이지네이션을 **경계값까지** 검증 |
| 5 | `size` 무제한이면 TEXT 없이도 대량 응답 | 메모리·응답 시간 | 상한 100 · 초과 시 400 |
| 6 | `difficulty`·`confidence` 가 미인덱스 | 대량 데이터에서 풀스캔 | **지금은 추가하지 않는다** — 행 0건·운영 없음. 주 필터 `status` 는 `idx_contribution_candidate_status`(V2:110)가 받고, 상세의 두 조회도 `idx_agent_run_candidate_stage`(V2:137)·`idx_generated_change_candidate_created`(V2:155)가 받는다. 실데이터가 쌓이는 #11 이후에 실측으로 정한다 |

**대외 호출 실패 시나리오** — **해당 없음.**

## 8. 복잡도

| Stage | 파일 수 | 복잡도 |
|-------|--------|--------|
| 1 | 5 | 낮음 |
| 2 | 2 | **중간** — 스크럽 누락이 곧 S-4 사고다 |
| 3 | 2 | 낮음 |
| 4 | 3 | 중간 — 프로젝션이 TEXT 를 안 읽는 것을 어떻게 증명하느냐가 관건 |
| 5 | 2 | 낮음 |

## 9. 문서 동기화 대상

| 대상 | 필요 | 이유 |
|---|---|---|
| `codemaps/architecture.md` | ✅ | `candidate` 에 application·adapter/out/persistence 가 처음 생긴다 + 「빈 배열 고정」 표기 |
| `README.md` | ✅ | 「`GET /api/candidates`는 빈 배열을 반환합니다」 + 「기여 후보 조회 API 경계」 — 조직 정책상 **API 변경 시 문서 갱신 필수** |
| `rules/context/project-overview.md` | ✅ | 「`GET /api/candidates` 는 **빈 배열 고정**」 |
| `docs/setup.md` | ✅ | 〃 |
| `docs/plans/PLAN-12.md` | ✅ | 불변식 ⑩ 인계 문장 정정 — #13 에 삭제 API 가 **없다** |
| `codemaps/data.md` | — | 스키마 변경 없음 |
| `codemaps/domain.md` | — | 상태·전이·규칙 변경 없음 (조회 전용) |
| `.env.example` · `README.md` · `glossary.md` | — | 새 환경변수·구조·용어 없음 |
| `open-questions.md` | — | 닫히는 미결 없음 |

⚠️ **마이그레이션 없음.** 번호를 잡지 않는다 (V4 #12 머지 · V5 #7 · V6 #8 진행 중).

## 10. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-25 | smileboy0014 | 초안 — 저장소 필터를 후속으로 분리 |
| 2026-09-25 | smileboy0014 | **rev 2** — 격리 검토 반영. 🔴 정렬 규약 부재(FR-10) · 🟡 `confidence` primitive NPE · S-4 규칙 테스트 · `analysis` 잔여 위험 · 프로젝션 뷰를 `application` 으로(Q-3) · `GeneratedChange` 최신 1건 · 문서 4곳 · PLAN-12 인계 정정. 🔵 `errorMessage` 근거 정정 · Hibernate 6 함정 · `StatementInspector` · 픽스처는 `@Sql` |
