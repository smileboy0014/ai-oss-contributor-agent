# 데이터 모델 코드맵

> 기준 — [PRD](../../docs/ai-oss-contributor-agent-prd.md) §22 Database ERD (v1.2 Draft).
> ✅ **7테이블 전부 실재하고**(Flyway `V1`~`V9` · 엔티티 7개 매핑) **읽고 쓰는 코드도 전부 있다**(2026-09-28 기준).
> 마지막까지 비어 있던 것은 `generated_change.commit_sha`·`test_result`·`review_result` 였다 — 컬럼은 V2 부터 있었지만
> 대입하는 코드가 어느 이슈에도 없었고(#18·#19·#20 이 서로에게 넘겼다), 2026-09-28 에 채웠다.
> 「스키마가 있다」와 「기능이 있다」를 혼동하지 않는다 — 실제로 혼동돼 있었다.
>
> 컬럼 정본은 마이그레이션 SQL 이다.

## 스키마는 마이그레이션이 정본이다 (2026-09-21 · Q-2 확정)

**Flyway** 를 쓴다. `ddl-auto` 는 **`validate`** 이고, 엔티티가 스키마를 만들지 않는다.

```
src/main/resources/db/migration/
├── V1__create_oss_repositories.sql      (적용된 파일이라 이름을 바꾸지 않는다)
├── V2__create_pipeline_tables.sql
├── V3__rename_oss_repositories_to_singular.sql
├── V4__add_candidate_attempt_and_version.sql
├── V5__policy_pending_reason_and_repository_scoped_run.sql
├── V6__add_issue_scan_cursor.sql
├── V7__add_issue_filter_columns.sql
├── V8__policy_resolution.sql
├── V9__policy_document_fingerprints.sql
├── V10__scan_execution_and_interval.sql     (#26 — scan_execution · scan_interval_minutes)
├── V11__oss_repository_unique_owner_name.sql (#114 — UNIQUE(owner, name))
└── V12__policy_commands_override.sql         (#102 — commands_overridden_at)
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

## 테이블 8개

```
oss_repository ──1:1──▶ repository_policy
       │      └──1:1──▶ scan_execution          ← ✅ V10 · #26
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
| `(owner, name)` | **UNIQUE** ✅ V11 (#114) | 🔴 URL 철자만 다른 이중 등록이 **같은 upstream 이슈에 Draft PR 둘**을 냈다. 등록 시 좌표 검증·대소문자 무시 중복 검사도 함께 |
| `enabled` | BOOLEAN NOT NULL | 스캔 대상 여부 |
| `last_scanned_at` | TIMESTAMP NULL | **언제 돌렸나** — 커서가 아니다(아래) |
| `issue_cursor_updated_at` | TIMESTAMP NULL | ✅ V6 — 이슈 증분 수집 커서. **어디까지 봤나** |
| `issue_cursor_etag` | VARCHAR(255) NULL | ✅ V6 — page 1 응답의 ETag |
| `scan_interval_minutes` | INTEGER NULL | ✅ V10 — 🔴 **NULL 이면 전역 기본값**(`scan.default-interval`) |
| `default_branch` | VARCHAR | **설계만** — 코드에 없다 |
| `language` | VARCHAR | 〃 |
| `build_tool` | VARCHAR | 〃 `gradle` / `maven` |
| `build_command` | VARCHAR | 〃 |

인덱스 후보 — `UNIQUE(url)` (있음) · `INDEX(enabled, last_scanned_at)` 스캔 대상 선별용.

⚠️ **`scan_interval_minutes` 는 0 을 「무제한」으로 읽지 않는다.** `isDueForScan` 이 양의 값만
받아들이고 나머지는 기본값으로 떨어뜨린다 — 0 이 통과하면 주기가 사라져 스케줄러가
**매 순회마다** 그 저장소를 돌린다.

#### ⚠️ `last_scanned_at` 은 커서가 아니다 (#8)

둘을 겹쳐 쓰면 **스캔이 실패해도 커서가 전진해 이슈를 영구히 건너뛴다.**

| 컬럼 | 뜻 | 언제 갱신되나 |
|---|---|---|
| `last_scanned_at` | 우리가 **언제 돌렸나** | 스캔을 요청·시작할 때 |
| `issue_cursor_updated_at` | 데이터를 **어디까지 봤나** | 🔴 **저장이 끝난 뒤에만** |

⚠️ **`issue_cursor_etag` 는 `issue_cursor_updated_at` 과 짝이다.** ETag 는 URL 단위로
유효한데 `since` 가 URL 에 들어간다 — 커서가 전진하면 ETag 를 `NULL` 로 버린다.
둘을 따로 갱신하는 코드를 만들지 않는다(`OssRepository.updateIssueScanCursor` 가 함께 받는다).

⚠️ **커서를 `MAX(issue.github_updated_at)` 으로 파생하지 않는다.** 검토했으나 기각했다 —
커서가 행 보존 정책에 묶여, 오래된 이슈를 정리하는 순간 `MAX` 가 뒤로 점프해 전량
재스캔이 터진다.

### `scan_execution` ✅ 실재 (V10 · #26)

저장소 하나의 **스캔 진행 상태 + 중복 방어**. 저장소당 1행이고 **덮어쓴다.**

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `repository_id` | BIGINT **PK** | FK → `oss_repository(id)` — 애그리거트 안이라 건다 |
| `phase` | VARCHAR(20) NOT NULL | `ScanPhase` — IDLE·QUEUED·RUNNING·SUCCEEDED·SKIPPED·FAILED |
| `started_at` · `finished_at` | TIMESTAMP NULL | |
| 🔴 `lease_expires_at` | TIMESTAMP NULL | **활성 판정이 국면과 함께 본다** — 아래 |
| `owner_token` | VARCHAR(64) NULL | 누가 잡았나. 🔴 **판정에 쓰지 않는다** |
| `failure_stage` · `failure_type` | VARCHAR(40)·VARCHAR(255) | 🔴 예외 **클래스 이름**만 (S-4) |
| `issues_saved` … `candidates_skipped` | INTEGER NOT NULL | 직전 집계 6개 |
| `has_more` | BOOLEAN NOT NULL | |
| `delayed_until` | TIMESTAMP NULL | 레이트리밋 — 🔴 실패가 아니라 **지연** |
| `skip_reason` | VARCHAR(40) NULL | `ScanSkipReason` |
| `has_result` | BOOLEAN NOT NULL | 「한 번도 안 돌았다」와 「돌았는데 0」을 가른다 |

`INDEX(phase, lease_expires_at)` — 만료된 리스를 훑는 축.

#### 🔴 왜 `oss_repository` 의 컬럼이 아닌가

`last_scanned_at` 이 이미 루트에 있어 컬럼 추가가 자연스러워 보이지만, 실행 상태는
**직전 집계 6개 + 플래그**를 들고 있다. 루트에 펴면 저장소를 읽을 때마다 따라온다 —
「애그리거트는 작게 유지한다」.

⚠️ 그래도 **애그리거트 멤버는 맞다.** `agent_run`·`generated_change` 가 멤버가 아닌 이유는
「무한정 자란다」인데, 이 테이블은 저장소당 1행이고 덮어쓴다.

#### 🔴 `lease_expires_at` — 없으면 방어가 스스로를 잠근다

메모리 구현에는 아무도 적어 두지 않은 안전장치가 있었다 — **재기동이 상태를 지운다.**
DB 로 옮기는 순간 그것이 사라져, 인스턴스가 `kill -9` 되면 `RUNNING` 행이 남고
그 저장소는 **영원히 409** 가 된다. 재기동해도 안 풀린다.

| 리스를 | 최악 | 되돌릴 수 있나 |
|---|---|---|
| **둔다** | 도는 스캔을 뺏어 **중복 스캔 1회** | ✅ 이슈 수집이 멱등(upsert)이다 |
| 두지 않는다 | 저장소가 **영구히 잠긴다** | 🔴 없다 |

가르는 것은 보수성이 아니라 **실패의 방향이 되돌릴 수 있는가**다.

#### 🔴 행은 **항상 존재**한다 — upsert 를 쓰지 않기 위해서다

자리 잡기는 **조건부 UPDATE 한 방**이라(SELECT 후 UPDATE 로 짜면 두 인스턴스가 그 사이를
통과한다) 행이 있어야 한다. 「없으면 만든다」를 원자적으로 하려면 `ON CONFLICT`·`MERGE` 가
필요하고 그것이 벤더 고유 문법이다 — Q-2b-1.

그래서 **V10 이 기존 저장소를 백필**하고, 신규는 **`OssRepository` 생성자가 함께 만든다**
(`cascade = PERSIST`). 등록 UseCase 가 기억해서 만드는 구조로 두면 언젠가 빠진다.

⚠️ 따라서 「행이 없다」는 정상 상태가 아니다 — `ScanExecutionNotRegisteredException` 으로
드러낸다. 조용히 「잠겨 있다」로 번역하면 등록 버그가 **영구 409 로 위장**된다.

### `repository_policy` ✅ 실재 (V2)

대상 저장소의 **기여 규약**. 없으면 구현 단계로 넘어가지 않는다 — [S-5](../rules/context/safety-boundaries.md).

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `repository_id` | BIGINT FK | 1:1 |
| `java_version` | VARCHAR | 샌드박스 이미지 선택 근거 |
| `build_command` · `test_command` | VARCHAR(255) | 〃 실행 명령. 🔴 LLM 추출값이 255 를 넘으면 **NULL(못 읽음)** 로 둔다 — 잘라 쓸 수 없다 (#102) |
| `commands_overridden_at` | TIMESTAMP NULL ✅ V12 (#102) | 사람이 `POST /policy/commands` 로 명령을 넣은 시각. 🔴 NULL 이 아니면 **재분석이 세 명령을 덮어쓰지 않는다** — 자동이 사람 판단을 다시 쓰지 않는다 |
| `issue_reference_required` | BOOLEAN | 커밋/PR 에 이슈 참조 필수 |
| `signoff_required` | BOOLEAN | DCO sign-off 필수 |
| `tests_required` | BOOLEAN | 테스트 동반 필수 |
| `ai_contribution_allowed` | BOOLEAN NULL | NULL = 판정 실패 = **보류**(허용 아님) — Q-8 |
| `contribution_rules` | TEXT | **판정의 정규화 결과(JSON)와 근거 경로.** 원문을 넣지 않는다 — 아래 |
| `pending_reason` | VARCHAR(1024) NULL | **왜 보류됐나** (V5 · #7). `경로=사유코드` 목록. 🔴 해소 뒤에도 **지우지 않는다** |
| `resolved_at` | TIMESTAMP NULL | **사람이 언제 보류를 풀었나** (V8 · #24). NULL = 기계 판정 |
| `resolution_note` | VARCHAR(1024) NULL | **사람이 왜 그렇게 판단했나** (V8 · #24) |
| `document_fingerprints` | VARCHAR(4000) NULL | **판정이 무엇을 보고 선 것인가** (V9 · #68). `경로=지문` 목록, 지문은 `absent` 또는 SHA-256 hex. 🔴 NULL = **비교 기준 없음**이지 「안 바뀜」이 아니다 |
| `documents_checked_at` | TIMESTAMP NULL | **문서를 실제로 다시 읽어 본 시각** (V9 · #68). `analyzed_at` 과 다르다 — 아래 |
| `analyzed_at` | TIMESTAMP | 규약은 바뀐다. **LLM 판정을 세운** 시각 |

**`ai_contribution_allowed` 를 NOT NULL DEFAULT true 로 두지 않는다.** 기본 허용은 S-5 위반을 기본값으로 만드는 것이다.

🔴 **`contribution_rules` 에 대상 저장소 원문을 넣지 않는다** (#7). `@ExternalText` 는 **표시만** 하고
스크럽을 실행하지 않으며, `PromptScrubber` 는 **LLM 송신 경로 전용**이다. 원문을 그대로 넣으면
스크럽을 한 번도 타지 않고 앉는다. 게다가 이 컬럼은 PR 본문 조립(#23)의 입력이 될 수 있어
**유출 종착지가 대상 저장소의 공개 PR** 이다. `ScrubbedRules` 값 타입을 거쳐야만 값이 들어간다.

⚠️ `pending_reason` 이 `TEXT` 가 아닌 이유 — 이 프로젝트에서 `TEXT` 는 「외부 텍스트」를 뜻하고
`@ExternalText` 가 강제된다(`ExternalTextMarkerTest`). 여기 들어가는 것은 **우리가 만든 사유 문자열**
이고 길이도 유계다(후보 경로 13개 × `경로=사유코드; `).

### 문서 변경 탐지 두 컬럼 — V9 · #68

「못 읽었다」와 「읽었는데 그대로다」가 구분되지 않아, **대상 저장소가 AI 기여를 금지했는데
그 문서를 못 읽으면 낡은 허용 판정이 그대로 굳었다**(S-5 위반이 진행 중인데 아무도 모른다).

| | |
|---|---|
| `document_fingerprints` | 다음 관측의 **비교 기준**. 지문이 같으면 **LLM 을 부르지 않는다** — 그래서 매 스캔 재확인이 성립하고, 「규약이 얼마나 자주 바뀌는가」를 몰라도 TTL 이 필요 없다 |
| `documents_checked_at` | 🔴 `analyzed_at` 과 **다른 것**이다. 지문이 같으면 LLM 을 안 부르므로 `analyzed_at` 은 전진하지 않는다. 그때 그것을 갱신하면 「분석했다」가 거짓말이 된다 |

🔴 **`documents_checked_at` 이 멈춰 있는 것이 「모르는 상태가 오래됐다」의 유일한 증거다.**
5xx·레이트리밋으로 응답을 못 받으면 지문을 구할 수 없어 변경 여부를 **원리적으로** 알 수 없다.

⚠️ `document_fingerprints` 가 `TEXT` 가 아닌 이유는 `pending_reason` 과 같다 — SHA-256 은
단방향이라 원문이 복원되지 않고 경로는 우리 상수다. **외부 텍스트가 아니다.**

### 보류 해소 두 컬럼 — V8 · #24

Q-8 이 「보류는 재분석·시간경과·횟수소진으로 풀리지 않는다」를 확정하면서 **푸는 경로가
하나도 없어졌다.** `POST /repositories/{id}/policy/resolution` 이 그 경로이고,
이 두 컬럼이 그 행위의 기록이다.

| 물음 | 답 |
|---|---|
| `resolved_at` 이 NULL 이면 | **기계 판정**이다. 「보류가 아니다」와 다른 말이다 |
| 해소가 곧 허용인가 | ❌ **아니다.** 사람이 읽고 「금지」로 닫는 것도 정상적인 해소다 |
| `pending_reason` 을 비우나 | ❌ **비우지 않는다.** `resolved_at` 이 이미 「보류 아님」을 말하고, 비우면 왜 보류였는지가 사라져 그 판단을 재검토할 수 없다 |

⚠️ **`resolution_note` 에 `@ExternalText` 를 달지 않는다.** 그 마커는 「길어서 따로 둔 외부
텍스트」(`TEXT` 컬럼)의 표시이고, 여기는 `VARCHAR(1024)` + **대입 지점에서 `TokenRedactor`**
라 `pending_reason`(#7)과 같은 취급이다. 마커를 달면 `ExternalTextScrubRegistryTest` 의
유령 행 검사에 걸린다.

⚠️ **입력 상한(1000)이 컬럼(1024)보다 짧다.** 두 축인 이유는 **스크럽이 마스킹하며 길이를
늘리기** 때문이다. 같은 값으로 두면 딱 맞는 입력이 스크럽 후에 넘친다 — API 가 1000 에서
400 을 돌려주고, 도메인이 마지막으로 한 번 더 자른다.

⚠️ `java_version`·`build_command`·`test_command` 는 **#7 이후에도 대체로 NULL 이다.**
문서 본문에서만 오고 빌드 설정 파싱은 #15 다. 「NULL 인 정책으로 샌드박스에 진입할 수 있는가」는
#15·#17 의 선결 과제다.

### `issue` ✅ 실재 (V2 · V6 · V7)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `repository_id` | BIGINT FK | |
| `github_issue_number` | INT NOT NULL | |
| `title` | VARCHAR | |
| `body` | TEXT | **대용량** — 목록 조회에서 제외 |
| `state` | VARCHAR | `open` 고정. ⚠️ **닫힌 이슈를 조회하지 않아 영원히 open 이다** (#8 → #14) |
| `url` | VARCHAR | |
| `labels` | VARCHAR | PRD 에 없지만 필터에 필요 (`good first issue` 등). 콤마 조인 |
| `comment_count` | INT | V7 · #9. 규칙의 **보류** 신호이자 #11 의 LLM 판정 입력 |
| `filter_result` | VARCHAR | `NULL`(미판정) · `PASSED` · `REJECTED` · `UNDECIDED`. **4상태다** |
| `filter_reason` | VARCHAR(512) | V7 · #9. `FilterReason` 이름을 콤마로 이은 것. **자유 텍스트가 아니다** — 아래 |
| `filter_priority` | SMALLINT | V7 · #9. 라벨 우선순위 점수. #11 이 **SQL 로 정렬**할 때 쓴다 |
| `filter_judged_at` | TIMESTAMP | V7 · #9. 「판정이 언제 섰나」. `updated_at`(행을 언제 건드렸나)과 뜻이 다르다 |
| `github_created_at` · `github_updated_at` | TIMESTAMP | **`github_updated_at` 이 증분 수집 커서다** |
| `created_at` · `updated_at` | TIMESTAMP | |

🔴 **`filter_reason` 은 `TEXT` 가 아니다** (V7 에서 내렸다). 이 프로젝트에서 `TEXT` 는
「길어서 따로 둔 외부 텍스트」를 뜻하고 `@ExternalText` 마커가 강제된다
(`ExternalTextMarkerTest`). 여기 들어가는 값은 `FilterReason` enum 의 이름 — **우리
어휘**다. 이슈 본문 발췌가 들어가면 대상 저장소 사용자가 쓴 임의 텍스트가 우리 DB 를
거쳐 LLM 프롬프트·PR 본문으로 흘러간다 (S-4). `repository_policy.pending_reason` 을
`VARCHAR` 로 둔 것과 같은 판단이다.

⚠️ **재판정 트리거는 `filter_result IS NULL` 이다.** 내용이 바뀌면 `Issue.updateFrom` 이
필터 4컬럼을 전부 비운다. 규칙·임계를 바꾼 것은 잡지 못하므로, 그때는 운영에서
`filter_judged_at` 으로 대상을 골라 비운다.

멱등키 — **`UNIQUE(repository_id, github_issue_number)`**.
없으면 재스캔마다 같은 이슈가 중복 적재되고, 후보도 중복 생성된다.

인덱스 후보 — `INDEX(repository_id, updated_at)` 증분 수집 · `INDEX(state, filter_result)` 후보 선별.

### `contribution_candidate` ✅ 실재 (V2 · V4)

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
| `attempt` | INT NOT NULL DEFAULT 0 | **`CODE→VERIFY→REVIEW` 한 바퀴** — Q-6 확정. 0 = 미착수. 상한 3, 도메인이 그보다 큰 값을 거부한다(불변식 ⑧) — V4 |
| `version` | BIGINT NOT NULL DEFAULT 0 | **낙관적 락.** 없으면 동시 전이로 같은 후보에 구현 사이클이 2개 생긴다 — 30분 샌드박스 ×2 · LLM 과금 ×2 — V4 |

멱등키 — **`UNIQUE(issue_id)`**.
인덱스 후보 — `INDEX(status)` 대시보드 · `INDEX(status, confidence DESC)` 추천 정렬.

### `agent_run` ✅ 실재 (V2)

파이프라인 한 단계의 **1회 실행 기록**. 비용·재시도의 유일한 근거다.

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `candidate_id` | BIGINT **NULL** | 🔴 `POLICY` 단계만 NULL — 아래 (V5 · #7) |
| `stage` | VARCHAR | `ANALYZE` / `PLAN` / `CODE` / `VERIFY` / `REVIEW` / **`POLICY`** |
| `attempt` | INT | **`CODE→VERIFY→REVIEW` 한 바퀴** — Q-6 확정. `ANALYZE`·`PLAN`·`POLICY` 행은 항상 1 이다(루프 밖) |
| `input_tokens` · `output_tokens` | INT | 비용 집계 |
| `status` | VARCHAR | `RUNNING` / `SUCCEEDED` / `FAILED` |
| `error_message` | TEXT | **스크럽 대상** — 스택트레이스에 토큰이 섞인다 |
| `started_at` · `finished_at` | TIMESTAMP | 단계별 소요 시간 |

🔴 **`candidate_id` 가 NULL 일 수 있다 — `POLICY` 단계뿐이다** (V5 · #7).

「모든 LLM 호출은 후보에 속한다」는 전제가 틀렸다. **규약 판정은 저장소 단위**이고 후보가
만들어지기 전에 일어난다. 가짜 `candidate_id`(0·-1)로 채우면 비용 장부와 MDC 가 오염되므로
전제를 고쳤다.

**전면 허용이 아니다** — 엔티티가 「`POLICY` 만 NULL, 나머지는 필수」를 양방향으로 강제한다
(`POLICY` 에 `candidate_id` 를 붙여도 거부). DB `CHECK` 로 쓰지 않은 이유는 스키마가
stage enum 문자열에 묶이기 때문이다.

인덱스 후보 — `INDEX(candidate_id, stage, attempt)`.

### `generated_change` ✅ 실재 (V2)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `candidate_id` | BIGINT FK | |
| `branch_name` | VARCHAR(512) | `oss-agent/issue-{n}-{slug}` — `record(...)` 에서 |
| `commit_sha` | VARCHAR(64) | **Fork 에 올라간 커밋(관측값).** `markPublished` 가 PR 게이트 뒤 push 성공 후 채운다. NULL = 아직 push 안 함 — PR 생성기가 이 값으로 「새 브랜치 생성 / 우리 브랜치 갱신」을 가른다 (#23) |
| `diff` | TEXT | **가장 큰 컬럼 · 스크럽 대상.** `record(...)` 유일 경로. 목록 조회에서 반드시 제외. 🔴 **Fork 에 올릴 파일의 정본** — PR 시점에 upstream 을 재clone 해 이것을 입힌다 |
| `test_result` | TEXT | 단계별 `StageResult.summary` 를 이은 것. `recordVerification` 유일 경로 (#19) |
| `review_result` | TEXT | `DiffReview` 판정·요약·지적. `recordReview` 유일 경로 (#20) |
| `created_at` | TIMESTAMP | 재시도 이력이 쌓이므로 정렬 기준 필요. ⚠️ 이 테이블만 `updated_at` 이 **없다** — 「모든 테이블에 둔다」의 예외이고, 행이 바퀴 안에서만 갱신되므로 안고 간다 |

후보당 N 행이다. 재시도할 때마다 새 행을 남기고 **덮어쓰지 않는다** — 무엇이 어떻게 바뀌었는지 추적이 사라진다.
`commit_sha`·`test_result`·`review_result` 는 **같은 행 안에서 뒤늦게 채워지는 것**이고, 행을 새로 만들지 않는다.

### `pull_request` ✅ 실재 (V2)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | |
| `candidate_id` | BIGINT FK **UNIQUE** | 후보당 PR 1건 |
| `fork_url` | VARCHAR NOT NULL | **쓰기 대상은 Fork 뿐** — S-1.<br>🔴 **조립하지 않고 GitHub 응답(`head.repo.html_url`)에서 읽는다**(#23) — fork 이름이 `{name}-1` 로 만들어지는 경우가 있고, 조립한 값은 우리의 **믿음**이지 관측이 아니다. 증거는 관측이어야 한다 |
| `branch_name` | VARCHAR | |
| `github_pr_number` | INT | |
| `pr_url` | VARCHAR | |
| `status` | VARCHAR | `DRAFT` 로 생성. **`draft` 아닌 상태로 만드는 경로를 두지 않는다** — S-2. DB `CHECK (status = 'DRAFT')` 가 마지막 방어 |
| `created_at` · `updated_at` | TIMESTAMP | |

멱등키 — **`UNIQUE(candidate_id)`** · 보조로 `UNIQUE(fork_url, branch_name)`.
없으면 재시도 시 같은 후보로 PR 이 두 개 열린다. **남의 저장소에 중복 PR 을 여는 것은 스팸으로 취급된다.**

✅ **#23 이 이 테이블의 첫 쓰기 경로를 만들었다.** 그전까지는 행이 조회 픽스처에만 있었다.

🔴 **멱등은 이 제약 하나가 아니라 셋이 함께 선다** — 하나만 믿으면 창이 남는다.

| 층 | 무엇을 막나 |
|---|---|
| 상태 전이(`READY_FOR_PR → PR_CREATED`) + `@Version` | 두 번째 **승인**. 종단이라 두 번째 호출은 409 다 |
| `DraftPrPublisher.findOpen` | upstream 에 **이미 열린 PR** — 새로 만들지 않고 붙인다 |
| `UNIQUE(candidate_id)` | 위 둘의 **창**을 빠져나온 동시 요청 |

⚠️ **생성 경로는 `PullRequest.draftFor` 하나**이고, 그것을 부르는 곳이 승인 경로뿐임을
`ApprovalGateArchitectureTest` 가 고정한다 — PR 생성 승인의 **증거**는 필드가 아니라
**이 행의 존재**인데, 행은 어느 경로로 만들어졌는지를 스스로 말하지 않기 때문이다.

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
| 2026-09-28 | gt.park | 머리말의 「읽고 쓰는 코드는 없다」 삭제 · 트리에 V9 · `generated_change` 세 컬럼의 대입 경로(`markPublished`·`recordVerification`·`recordReview`) 명시 · `pull_request.updated_at` |
| 2026-09-26 | smileboy0014 | `repository_policy` 에 보류 해소 2컬럼 (V8 · #24) |
| 2026-09-27 | smileboy0014 | `repository_policy` 에 문서 지문·확인시각 2컬럼 (V9 · #68) |
| 2026-09-26 | smileboy0014 | `issue` 에 필터 4컬럼 (V7 · #9) · `filter_reason` 을 TEXT 에서 VARCHAR 로 |
| 2026-09-22 | smileboy0014 | 7테이블 실재로 전환 (V2 · #5) · 스키마 정본을 마이그레이션으로 명시 |
| 2026-09-18 | smileboy0014 | 초안 생성 — PRD v1.1 §22 ERD 기준 · 멱등키·스크럽 대상 컬럼 지정 |
