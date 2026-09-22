# 데이터 모델 코드맵

> 기준 — [PRD](../../docs/ai-oss-contributor-agent-prd.md) §22 Database ERD (v1.1 Draft).
> ✅ **7테이블 전부 실재한다** (Flyway `V1`·`V2` · 엔티티 7개 매핑 · 2026-09-22 · #5).
> ⚠️ 다만 **읽고 쓰는 코드는 없다** — Spring Data 인터페이스·UseCase 는 각 기능 이슈(#7·#8·#11…) 소관이다.
> 「스키마가 있다」와 「기능이 있다」를 혼동하지 않는다.
>
> 아래 표의 ❌ 표시는 **설계만 있던 시점의 것**이며 더는 유효하지 않다. 컬럼 정본은 마이그레이션 SQL 이다.

## 스키마는 마이그레이션이 정본이다 (2026-09-21 · Q-2 확정)

**Flyway** 를 쓴다. `ddl-auto` 는 **`validate`** 이고, 엔티티가 스키마를 만들지 않는다.

```
src/main/resources/db/migration/
├── V1__create_oss_repositories.sql      (적용된 파일이라 이름을 바꾸지 않는다)
├── V2__create_pipeline_tables.sql
└── V3__rename_oss_repositories_to_singular.sql
```

| 규칙 | 이유 |
|---|---|
| 스키마 변경은 **새 마이그레이션 파일**로만 | 적용된 파일을 고치면 체크섬이 깨져 기동이 실패한다 |
| `ddl-auto` 를 **`update` 로 되돌리지 않는다** | 엔티티가 스키마를 만들기 시작하면 마이그레이션과 실제 스키마가 갈라지고, 그 사실이 운영에서야 드러난다 |
| **벤더 고유 문법 금지** — JSONB · 파티셔닝 · TEXT 계열 차이 | 같은 SQL 한 벌이 **H2(로컬 기본값)와 PostgreSQL 양쪽**에서 돌아야 한다 |
| 엔티티를 바꾸면 **같은 커밋에 마이그레이션**을 넣는다 | `validate` 가 기동 시점에 잡아내지만, 그때는 이미 늦다 |

✅ **대용량 텍스트는 `TEXT` + `@Column(columnDefinition = "TEXT")` 로 확정됐다** (2026-09-22 · #5).
H2·PostgreSQL 양쪽에서 실제로 확인했다 — `./gradlew build`(H2) · `SchemaMigrationTest`(Testcontainers).
`@Lob` 은 쓰지 않는다: PostgreSQL 에서 `oid` 로 매핑돼 라지오브젝트 테이블을 따로 쓰게 된다.
JSONB·파티셔닝은 여전히 금지다 — [`open-questions.md`](../rules/context/open-questions.md) **Q-2b-1**.

## 네이밍 컨벤션

- 테이블·컬럼은 **snake_case**. 테이블명은 **단수** (`oss_repository` · `issue` · `agent_run`)
  - PRD §22 ERD 가 단수이고 JPA/Hibernate 기본값도 단수다. 한 행이 곧 한 개체라는 관계형 관례를 따른다
  - 복수/단수는 업계가 갈리는 주제지만 **정하고 일관되게 가는 것**이 전부다. 이 프로젝트는 단수다
- PK 는 **`id`** (BIGINT AUTO_INCREMENT). FK 는 `{대상단수}_id` — `repository_id` · `candidate_id`
- 시각은 **`~_at`** (TIMESTAMP) — `created_at` · `updated_at` · `last_scanned_at` · `started_at` · `finished_at`
- BOOLEAN 은 **`is_~`** 또는 서술형 — `enabled` · `tests_required` · `breaking_change`
- 개수·크기는 **`~_count`**, 토큰은 `input_tokens` · `output_tokens`

PRD ERD 는 `created_at`/`updated_at` 를 `ISSUE` 에만 그렸지만, **모든 테이블에 둔다.** 파이프라인 지연을 추적할 수 없으면 병목을 못 찾는다.

## 설계 원칙

| 원칙 | 이유 |
|---|---|
| **시크릿은 어떤 컬럼에도 넣지 않는다** | `contribution_rules` · `diff` · `error_message` · `analysis` 는 전부 외부에서 온 텍스트다. 토큰이 섞여 들어오면 DB 덤프·로그로 유출된다 — [S-4](../rules/context/safety-boundaries.md). 적재 전 스크럽한다 |
| **대용량 텍스트는 별도 테이블에 격리** | `diff` · `analysis` · `contribution_rules` 는 수십 KB 다. 후보 목록 조회에 딸려 오면 `GET /api/candidates` 가 메가바이트를 뱉는다. 목록 쿼리에서 반드시 제외한다 |
| **LLM 원문 응답을 그대로 컬럼에 담지 않는다** | 파싱 결과를 구조화 컬럼으로 저장하고 원문은 필요분만. 원문이 정본이 되면 도메인이 모델 출력 포맷에 묶인다 |
| **토큰·비용은 실행 단위로 남긴다** | `agent_run` 에 `input_tokens`·`output_tokens`. 집계가 없으면 재시도 루프가 조용히 돈을 태운다 |
| **외부 식별자에 UNIQUE** | GitHub 이슈 번호·PR 번호는 재실행 멱등성의 유일한 근거다 |
| **후보는 이슈당 1건** | 같은 이슈로 후보가 둘 생기면 같은 작업을 두 번 하고 PR 도 두 개 난다 |
| **종단 상태를 지우지 않는다** | `REJECTED`·`FAILED` 행을 삭제하면 같은 이슈를 다음 스캔에서 또 분석한다. LLM 비용이 반복된다 |

---

## 테이블 7개

```
oss_repository ──1:1──▶ repository_policy
       │
       └──1:N──▶ issue ──1:0..1──▶ contribution_candidate
                                          │
                                          ├──1:N──▶ agent_run
                                          ├──1:N──▶ generated_change
                                          └──1:0..1──▶ pull_request
```

### `oss_repository` ✅ 실재 (V1)

대상 저장소 등록 정보. 이 프로젝트 자신이 아니라 **기여 대상**이다.

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `owner` | VARCHAR NOT NULL | `spring-projects` |
| `name` | VARCHAR NOT NULL | `spring-kafka` |
| `url` | VARCHAR NOT NULL **UNIQUE** | 중복 등록 차단의 근거 |
| `enabled` | BOOLEAN NOT NULL | 스캔 대상 여부 |
| `last_scanned_at` | TIMESTAMP NULL | 스캔 커서 |
| `default_branch` | VARCHAR | **설계만** — 코드에 없다 |
| `language` | VARCHAR | 〃 |
| `build_tool` | VARCHAR | 〃 `gradle` / `maven` |
| `build_command` | VARCHAR | 〃 |

인덱스 후보 — `UNIQUE(url)` (있음) · `INDEX(enabled, last_scanned_at)` 스캔 대상 선별용.

### `repository_policy` ✅ 실재 (V2)

대상 저장소의 **기여 규약**. 없으면 구현 단계로 넘어가지 않는다 — [S-5](../rules/context/safety-boundaries.md).

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `repository_id` | BIGINT FK | 1:1 |
| `java_version` | VARCHAR | 샌드박스 이미지 선택 근거 |
| `build_command` · `test_command` | VARCHAR | 〃 실행 명령 |
| `issue_reference_required` | BOOLEAN | 커밋/PR 에 이슈 참조 필수 |
| `signoff_required` | BOOLEAN | DCO sign-off 필수 |
| `tests_required` | BOOLEAN | 테스트 동반 필수 |
| `ai_contribution_allowed` | BOOLEAN NULL | **PRD 에 없지만 추가해야 한다.** NULL = 판정 실패 = **보류**(허용 아님) — Q-8 |
| `contribution_rules` | TEXT | 원문 요약. **대용량 · 스크럽 대상** |
| `analyzed_at` | TIMESTAMP | 규약은 바뀐다. 재분석 주기 판단 근거 |

**`ai_contribution_allowed` 를 NOT NULL DEFAULT true 로 두지 않는다.** 기본 허용은 S-5 위반을 기본값으로 만드는 것이다.

### `issue` ✅ 실재 (V2)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `repository_id` | BIGINT FK | |
| `github_issue_number` | INT NOT NULL | |
| `title` | VARCHAR | |
| `body` | TEXT | **대용량** — 목록 조회에서 제외 |
| `state` | VARCHAR | `OPEN` / `CLOSED` |
| `url` | VARCHAR | |
| `labels` | VARCHAR | PRD 에 없지만 필터에 필요 (`good first issue` 등) |
| `filter_result` · `filter_reason` | VARCHAR · TEXT | 왜 배제됐는지. 없으면 같은 이슈를 매번 다시 판정한다 |
| `created_at` · `updated_at` | TIMESTAMP | **`updated_at` 이 증분 수집 커서다** |

멱등키 — **`UNIQUE(repository_id, github_issue_number)`**.
없으면 재스캔마다 같은 이슈가 중복 적재되고, 후보도 중복 생성된다.

인덱스 후보 — `INDEX(repository_id, updated_at)` 증분 수집 · `INDEX(state, filter_result)` 후보 선별.

### `contribution_candidate` ✅ 실재 (V2)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `issue_id` | BIGINT FK **UNIQUE** | **이슈당 후보 1건** |
| `category` | VARCHAR | `bug` / `enhancement` / `documentation` |
| `difficulty` | VARCHAR | `EASY` / `MEDIUM` / `HARD` |
| `estimated_files` · `estimated_loc` | INT | |
| `implementation_feasible` | BOOLEAN | |
| `breaking_change` | BOOLEAN | true 면 후보 배제 대상 |
| `confidence` | DECIMAL(3,2) | 0.00 ~ 1.00 |
| `analysis` | TEXT | **대용량 · 스크럽 대상** |
| `status` | VARCHAR NOT NULL | `CandidateStatus` 11종 — [`domain.md`](./domain.md) |
| `selected_at` | TIMESTAMP NULL | **사람이 고른 시각.** NULL 이면 구현 단계로 못 간다 (S-6) |

멱등키 — **`UNIQUE(issue_id)`**.
인덱스 후보 — `INDEX(status)` 대시보드 · `INDEX(status, confidence DESC)` 추천 정렬.

### `agent_run` ✅ 실재 (V2)

파이프라인 한 단계의 **1회 실행 기록**. 비용·재시도의 유일한 근거다.

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `candidate_id` | BIGINT FK | |
| `stage` | VARCHAR | `ANALYZE` / `PLAN` / `CODE` / `VERIFY` / `REVIEW` |
| `attempt` | INT | **의미가 미정이다** — 단계별 독립 카운터인지 후보 통합인지 → Q-6 |
| `input_tokens` · `output_tokens` | INT | 비용 집계 |
| `status` | VARCHAR | `RUNNING` / `SUCCEEDED` / `FAILED` |
| `error_message` | TEXT | **스크럽 대상** — 스택트레이스에 토큰이 섞인다 |
| `started_at` · `finished_at` | TIMESTAMP | 단계별 소요 시간 |

인덱스 후보 — `INDEX(candidate_id, stage, attempt)`.

### `generated_change` ✅ 실재 (V2)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `candidate_id` | BIGINT FK | |
| `branch_name` | VARCHAR | `oss-agent/issue-{n}-{slug}` |
| `commit_sha` | VARCHAR | |
| `diff` | TEXT/LONGTEXT | **가장 큰 컬럼 · 스크럽 대상.** 목록 조회에서 반드시 제외 |
| `test_result` · `review_result` | TEXT | **대용량** |
| `created_at` | TIMESTAMP | 재시도 이력이 쌓이므로 정렬 기준 필요 |

후보당 N 행이다. 재시도할 때마다 새 행을 남기고 **덮어쓰지 않는다** — 무엇이 어떻게 바뀌었는지 추적이 사라진다.

### `pull_request` ✅ 실재 (V2)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `candidate_id` | BIGINT FK **UNIQUE** | 후보당 PR 1건 |
| `fork_url` | VARCHAR NOT NULL | **쓰기 대상은 Fork 뿐** — S-1 |
| `branch_name` | VARCHAR | |
| `github_pr_number` | INT | |
| `pr_url` | VARCHAR | |
| `status` | VARCHAR | `DRAFT` 로 생성. **`draft` 아닌 상태로 만드는 경로를 두지 않는다** — S-2 |
| `created_at` | TIMESTAMP | |

멱등키 — **`UNIQUE(candidate_id)`** · 보조로 `UNIQUE(fork_url, branch_name)`.
없으면 재시도 시 같은 후보로 PR 이 두 개 열린다. **남의 저장소에 중복 PR 을 여는 것은 스팸으로 취급된다.**

---

## 멱등키 요약

| 경로 | 키 | 없으면 |
|---|---|---|
| 저장소 등록 | `UNIQUE(url)` | 같은 저장소 중복 등록 |
| 이슈 재수집 | `UNIQUE(repository_id, github_issue_number)` | 이슈 중복 적재 |
| 후보 생성 | `UNIQUE(issue_id)` | 같은 작업 이중 실행 |
| PR 생성 | `UNIQUE(candidate_id)` | **대상 저장소에 중복 PR** |

## 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-22 | smileboy0014 | 7테이블 실재로 전환 (V2 · #5) · 스키마 정본을 마이그레이션으로 명시 |
| 2026-09-18 | smileboy0014 | 초안 생성 — PRD v1.1 §22 ERD 기준 · 멱등키·스크럽 대상 컬럼 지정 |
