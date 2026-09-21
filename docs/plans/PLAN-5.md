# PLAN-5: ERD 7테이블 스키마·마이그레이션 작성

**이슈**: [#5](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/5)
**타입**: feature
**작성일**: 2026-09-22

---

## 1. 요구사항

### 배경

PRD §22 ERD 가 7테이블을 정의했지만 실재하는 것은 `oss_repositories` 하나다.
#3 에서 Flyway 를 깔았으니 나머지 6개를 만든다. **이 스키마는 안전 경계 5개 조항의 물리적 근거**다 —
「사람이 골랐는가」(`selected_at`)·「AI 기여가 허용되는가」(`ai_contribution_allowed`)·
「PR 이 중복되지 않는가」(`UNIQUE(candidate_id)`)가 전부 컬럼과 제약으로 표현된다.

### 기능 요구사항 (FR)

| # | 요구 |
|---|---|
| FR-1 | 테이블 6개를 마이그레이션으로 생성한다 — `repository_policies` · `issues` · `contribution_candidates` · `agent_runs` · `generated_changes` · `pull_requests` |
| FR-2 | 각 테이블의 JPA 엔티티를 **소유 도메인**의 `domain/` 에 배치한다 |
| FR-3 | 멱등키 4종을 UNIQUE 제약으로 건다 |
| FR-4 | 대용량 텍스트 컬럼 타입을 확정한다 — H2·PostgreSQL 공통 |
| FR-5 | 시크릿 스크럽 대상 컬럼을 **코드에서 식별 가능**하게 표시한다 |
| FR-6 | Testcontainers PostgreSQL 로 마이그레이션·매핑을 검증한다 |

### 비기능 요구사항 (NFR)

| # | 요구 |
|---|---|
| NFR-1 | `./gradlew build` 가 H2 에서 통과한다 (`validate` 포함) |
| NFR-2 | 같은 마이그레이션 SQL 이 PostgreSQL 에서도 돈다 (Q-2b) |
| NFR-3 | 엔티티 추가가 기존 API 동작을 바꾸지 않는다 |

---

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

**5개 조항 접촉.** 이 스키마가 각 조항의 물리적 근거가 된다.

| 조항 | 접촉 지점 | 어떻게 지킬 것인가 |
|---|---|---|
| `S-1` | `pull_requests.fork_url` | `NOT NULL`. 쓰기 대상이 Fork 임을 데이터로 고정한다. upstream URL 을 담는 컬럼을 두지 않는다 |
| `S-2` | `pull_requests.status` | 기본값 `DRAFT`. **`UNIQUE(candidate_id)`** 로 재시도 시 중복 PR 을 DB 가 막는다 — 남의 저장소에 중복 PR 은 스팸이다 |
| `S-4` | `contribution_rules` · `analysis` · `diff` · `test_result` · `review_result` · `error_message` | **6개 컬럼이 시크릿 유출 경로다.** 전부 외부(대상 저장소·LLM·빌드 로그)에서 온 텍스트다. 마커 애노테이션으로 표시해 #28 이 일괄 처리할 수 있게 한다 |
| `S-5` | `repository_policies.ai_contribution_allowed` | **`BOOLEAN NULL`** — `NOT NULL DEFAULT true` 로 두지 않는다. 기본 허용은 S-5 위반을 기본값으로 만드는 것이다. NULL = 판정 실패 = **보류** |
| `S-6` | `contribution_candidates.selected_at` | `TIMESTAMP NULL`. NULL 이면 구현 단계로 못 간다. 「사람이 골랐다」의 유일한 증거 |

`S-3` 미접촉 — 샌드박스 실행 경로가 이 작업에 없다.

### 미결 대조 (Q-1 ~ Q-10)

| Q | 판정 | 처리 |
|---|---|---|
| `Q-2b` | 🟡 접촉 | **이 이슈가 바로 그 갈림길이다.** 대용량 텍스트를 H2·PostgreSQL 공통 타입으로 쓰고 **양쪽 빌드로 검증**한다. 갈라지면 그때 벤더 분리를 판단 |
| `Q-6` | 🟡 접촉 | `agent_runs.attempt` 의 **의미는 미정**. 컬럼만 만들고 해석은 #21 에 넘긴다. 이 가정을 PR 본문에 명시 |
| `Q-7` | ✅ **닫힘** | Lombok **도입** (2026-09-22 결정). 엔티티 7개의 보일러플레이트가 크다 |
| `Q-8` | 🟡 접촉 | `ai_contribution_allowed` NULL=보류는 `data.md` 에 이미 결정돼 있다. 임계 정책(보류가 쌓일 때)은 #7 소관 |
| `Q-9` | 🟡 **부분 해소** | **Testcontainers 를 이번에 도입한다** (2026-09-22 결정). DB 대역만 해결되고 GitHub·LLM 대역은 #4 에 남는다 |

---

## 3. 스코프

### 포함

- 마이그레이션 `V2` — 테이블 6개
- 엔티티 6개 + 소유 도메인 배치
- Lombok 배선
- Testcontainers 배선 + 마이그레이션 검증 테스트
- 시크릿 마커 애노테이션

### 제외

| 항목 | 왜 |
|---|---|
| Repository(Spring Data) 인터페이스 | 쓰는 쪽(#7·#8·#11)이 필요할 때 만든다. 미리 만들면 쓰지 않는 코드가 쌓인다 |
| UseCase · API | 각 기능 이슈 소관 |
| `attempt` 의미 확정 | #21 (Q-6) |
| GitHub·LLM 테스트 대역 | #4 (Q-9 나머지) |

---

## 4. 기술 설계

### 소유 도메인 배치

| 테이블 | 엔티티 | 위치 |
|---|---|---|
| `repository_policies` | `RepositoryPolicy` | `repository/domain/` |
| `issues` | `Issue` | `issue/domain/` |
| `contribution_candidates` | `ContributionCandidate` | `candidate/domain/` |
| `agent_runs` | `AgentRun` | `agent/domain/` |
| `generated_changes` | `GeneratedChange` | `agent/domain/` |
| `pull_requests` | `PullRequest` | `pullrequest/domain/` |

`generated_changes` 는 `candidate_id` FK 를 갖지만 **`agent` 소유**다. 후보의 속성이 아니라
**에이전트 실행의 산출물**이고, 재시도마다 새 행이 쌓인다.

### 🔴 도메인 간 참조는 값으로만

```java
// ❌ 크로스 도메인 @ManyToOne — architecture.md 규율 ④ 위반
@ManyToOne
private ContributionCandidate candidate;

// ✅ 값만 보관
@Column(name = "candidate_id", nullable = false)
private Long candidateId;
```

**이유** — `@ManyToOne` 을 걸면 `agent` 가 `candidate` 의 엔티티 클래스를 컴파일 의존하게 된다.
한 번 얽히면 도메인 분리가 불가능해진다. 스키마가 하나여도 **코드 경계는 지킨다.**

물리 FK 제약은 건다(같은 스키마·단일 DB라 참조 무결성의 이득이 크다). 걸리는 것은
**JPA 연관관계이지 DB 제약이 아니다.**

### 대용량 텍스트 타입 (Q-2b)

```sql
contribution_rules TEXT
```

`TEXT` 는 PostgreSQL 네이티브이고 H2 2.x 도 지원한다(내부적으로 CLOB).
**빌드로 검증한다** — 안 되면 `VARCHAR(N)` 상한 또는 벤더 분리로 전환.

엔티티에서는 `@Column(columnDefinition = "TEXT")` 가 아니라 **`@Lob`** 을 쓰지 않는다 —
`@Lob` 은 PostgreSQL 에서 `oid` 로 매핑돼 라지오브젝트 테이블을 따로 쓰게 된다. 그냥 `String` + `TEXT` 컬럼이다.

### 시크릿 마커 (S-4)

```java
/** 외부에서 온 텍스트. 적재 전 스크럽한다 — S-4. #28 이 이 마커를 근거로 일괄 처리한다. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ExternalText {}
```

`support/` 에 둔다. 도메인이 아니고 기술도 아닌 **횡단 표시**다.
지금은 표시만 하고 스크럽 구현은 #28 이다 — **표시가 없으면 #28 이 어느 컬럼을 훑어야 할지 모른다.**

### 멱등키

| 테이블 | UNIQUE | 없으면 |
|---|---|---|
| `issues` | `(repository_id, github_issue_number)` | 재스캔마다 이슈 중복 적재 |
| `contribution_candidates` | `(issue_id)` | 같은 작업 이중 실행 |
| `pull_requests` | `(candidate_id)` | **대상 저장소에 중복 PR — 스팸** |
| `pull_requests` | `(fork_url, branch_name)` | 같은 브랜치 재사용 충돌 |

### 인덱스

| 테이블 | 인덱스 | 용도 |
|---|---|---|
| `issues` | `(repository_id, updated_at)` | 증분 수집 커서 |
| `issues` | `(state, filter_result)` | 후보 선별 |
| `contribution_candidates` | `(status)` | 대시보드 |
| `agent_runs` | `(candidate_id, stage, attempt)` | 실행 이력 조회 |
| `generated_changes` | `(candidate_id, created_at)` | 최신 변경분 |

---

## 5. 구현 순서

### 실행 모드: sequential

파일 의존이 겹친다(마이그레이션 → 엔티티 → 테스트). 병렬 이득이 없다.

| 단계 | 내용 | 검증 |
|---|---|---|
| 1 | Lombok + Testcontainers 배선 | `./gradlew build` |
| 2 | `ExternalText` 마커 | 컴파일 |
| 3 | `V2__create_pipeline_tables.sql` | H2 build |
| 4 | 엔티티 6개 | `validate` 통과 |
| 5 | Testcontainers 마이그레이션 검증 테스트 | PostgreSQL 실행 |
| 6 | 문서 동기화 | — |

마이그레이션은 **`V2` 하나**로 묶는다. 6테이블이 한 덩어리로 의미를 갖고, FK 순서가 한 파일 안에서 보장된다.

---

## 6. 테스트 계획

| 테스트 | 무엇을 보호하나 |
|---|---|
| `contextLoads` (기존, H2) | 마이그레이션 + `validate` 정합 |
| **`SchemaMigrationTest`** (Testcontainers PostgreSQL) | 같은 SQL 이 PostgreSQL 에서도 도는가 (Q-2b) · 테이블 7개 · UNIQUE 4종 존재 |
| `ExternalTextMarkerTest` | **스크럽 대상 6개 필드에 마커가 붙어 있는가** — 리플렉션으로 검사. 새 컬럼이 마커 없이 들어오는 것을 막는다 |

`ExternalTextMarkerTest` 가 이 PR 의 핵심 안전장치다. 마커는 사람이 붙이는 것이라 빠뜨리기 쉽고,
빠뜨리면 #28 의 스크럽이 그 컬럼을 지나친다.

---

## 7. 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| **`TEXT` 가 H2·PostgreSQL 에서 다르게 동작** | Q-2b 가 여기서 터진다 | 양쪽 빌드로 즉시 확인. 갈라지면 벤더 분리 또는 VARCHAR 상한 |
| Hibernate `validate` 가 타입 불일치로 실패 | 기동 불가 | #3 에서 겪은 것과 같은 패턴 — `TIMESTAMP(6) WITH TIME ZONE` 등 실제 생성 타입을 확인하며 맞춘다 |
| Lombok 도입이 기존 빌드를 깨뜨림 | 전체 실패 | 단계 1 에서 단독 검증 후 진행 |
| Testcontainers 가 Docker 를 요구 | Docker 없는 환경에서 테스트 실패 | `@EnabledIfDockerAvailable` 류로 **건너뛰되 「통과」라고 하지 않는다** |
| 엔티티 6개를 한 PR 에 = 리뷰 부담 | 놓침 | 계약 표면 절에 스키마 변경을 먼저 적는다 |

---

## 8. 복잡도

| 단계 | 파일 수 | 복잡도 |
|---|---|---|
| 1 배선 | 1 | 낮음 |
| 2 마커 | 1 | 낮음 |
| 3 마이그레이션 | 1 | **높음** — 제약·인덱스가 안전 경계와 직결 |
| 4 엔티티 | 6 | 중간 |
| 5 테스트 | 2 | 중간 |
| 6 문서 | 3~4 | 낮음 |

---

## 9. 문서 동기화 대상

| 대상 | 내용 |
|---|---|
| `codemaps/data.md` | ❌ 설계만 → ✅ 실재로 전환 · 실제 타입 반영 |
| `rules/context/open-questions.md` | Q-7 닫힘 · Q-9 부분 해소 · Q-2b 검증 결과 |
| `rules/context/project-overview.md` | 「테이블 1개만 존재」 정정 |
| `rules/conventions/testing-philosophy.md` | Testcontainers 실제 도입 반영 |

---

## 10. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-22 | smileboy0014 | 초안 — 게이트 판정(S 5건 · Q 5건) 포함 |
