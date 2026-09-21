# 미결정 대장

> 기준일 **2026-09-18**. 정하지 않고 코드를 쓰면 되돌리기 어려운 것들을 모았다.
> 여기 있는 항목을 만나면 **추측으로 채우지 말고 확인**한다. 확인이 불가능하면 가정을 명시하고 진행한 뒤 PR 본문에 남긴다.

## ⚠️ 문서 상태

[PRD](../../../docs/ai-oss-contributor-agent-prd.md) 는 **v1.1 Draft** 다. 아키텍처 다이어그램은 상세하지만
**구현 결정(라이브러리·도구·배치)은 대부분 비어 있다.** 이 대장은 그 빈칸이다.

---

## 🔴 착수를 막는 것

### Q-1. ✅ GitHub 연동 방식 — **classic PAT + Spring RestClient 직접 구현으로 확정** (2026-09-21 · #2)

**선택지가 하나뿐이었다.** PAT 과 GitHub App 의 트레이드오프 문제가 아니라, **둘 중 하나만 동작한다.**

| 방식 | Fork 생성 | **upstream 에 PR 생성** | 판정 |
|---|---|---|---|
| fine-grained PAT | ✅ | ❌ `403 Resource not accessible by personal access token` | **불가** |
| GitHub App (설치 토큰) | 공개 저장소는 가능 | ❌ upstream 이 우리 App 을 설치할 리 없다 | **불가** |
| GitHub App (user-to-server OAuth) | ✅ | ✅ 사용자 대행이라 가능 | 가능하나 OAuth 플로우 필요 |
| **classic PAT (`public_repo`)** | ✅ | ✅ | **채택** |

PR 생성은 **대상 저장소 소유자 수준의 권한**을 요구한다. fine-grained PAT 은 내가 소유하거나 멤버인
저장소로만 발급되므로, `spring-projects/spring-kafka` 에 PR 을 열 토큰을 **애초에 만들 수 없다.**
GitHub 로드맵 #600 에 올라가 있으나 미해결이고, 커뮤니티의 일관된 워크어라운드는 classic PAT 회귀다.
GitHub App 은 권한이 **설치 단위**라 설치되지 않은 upstream 에서는 동작하지 않는다.

**라이브러리 — Spring `RestClient` 직접 구현.** `hub4j/github-api` 는 활발하지만(2026-09 push) 이 프로젝트와 어긋난다.

| 항목 | hub4j 실태 |
|---|---|
| 안정 버전 | 1.330 (2025-09). 이후 2.0-rc 만 7번 — 1년 넘게 RC |
| 1차 레이트리밋 | ✅ `GitHubRateLimitChecker` 내장 |
| **2차 레이트리밋(403)** | ❌ 미구현 (#1975 open, 2024-10~) — 어차피 직접 짜야 한다 |
| **ETag 조건부 요청** | △ 라이브러리 기능이 아니라 OkHttp 디스크 캐시 위임 (#505, 2019). `updated_at` 커서와 맞물리기 어렵고 의존성이 붙는다 |

우리가 쓸 API 표면은 6개 남짓(저장소 메타·파일·이슈 목록·fork·push·PR)인데,
까다로운 요구 3개(ETag 커서 · 레이트리밋 헤더 · 403 구분)가 전부 **직접 제어**를 요구한다.

**S-1 에 미친 영향** — classic PAT 은 저장소별 권한 제한이 불가능해 「토큰 권한 미부여」를 1차 방어로 쓸 수 없다.
[`safety-boundaries.md`](./safety-boundaries.md) S-1 을 같은 커밋에서 개정했다. **코드 어설션이 유일한 방어**다.

**남은 것**
- PRD §25 의 「GitHub App · 최소 권한」은 이 시나리오에서 성립하지 않는다 — **PRD 개정 필요**
- 다중 사용자로 확장할 때의 경로는 **GitHub App + user-to-server OAuth** 다. 능력 인터페이스를
  그쪽으로 갈아끼울 수 있게 설계한다 (#6)

### Q-2. ✅ 스키마 마이그레이션 도구 — **Flyway 로 확정** (2026-09-21 · #3)

SQL 을 그대로 쓴다. 기준 프로젝트(`torder-membership-crm`)와 같아 두 저장소를 오가는 비용이 없다.

| 항목 | 값 |
|---|---|
| 도구 | **Flyway** (Boot 3.5.0 BOM 이 11.7.2 관리 — 버전 선언 불필요) |
| 아티팩트 | `flyway-core` + **`flyway-database-postgresql`** |
| 경로 | `src/main/resources/db/migration` (Flyway 기본값) |
| `ddl-auto` | **`validate`** — `update` 로 되돌리지 않는다 |
| `baseline-on-migrate` | **`false`** |

⚠️ **Flyway 10 부터 DB 별 지원이 모듈로 분리됐다.** `flyway-database-postgresql` 이 없으면
PostgreSQL 에서 기동하지 않는다. H2 는 core 에 남아 있어 별도 모듈이 없다
(`flyway-database-h2` 아티팩트는 **존재하지 않는다** — 찾지 말 것).

`baseline-on-migrate` 를 켜지 않는 이유 — 켜면 「이미 있는 스키마」를 조용히 인정해
**마이그레이션 누락이 드러나지 않는다.** 빈 DB 에서 V1 부터 쌓는다.

#### 딸려 나온 결정 — H2 를 어떻게 하나

`ddl-auto: validate` 로 바꾸면 마이그레이션 SQL 이 **H2(로컬 기본값)와 PostgreSQL 양쪽에서** 돌아야 한다.

**단일 SQL 한 벌로 간다.** 벤더별 분리(`{vendor}` 플레이스홀더)도, H2 제거도 하지 않았다.
지금 규모에서는 공통 문법으로 충분하고, 변경이 가장 작다.

**대신 제약이 생긴다** — 마이그레이션에 **벤더 고유 문법을 쓰지 않는다**(JSONB · 파티셔닝 · TEXT 계열 차이).

🟡 **#5 에서 재검토한다.** [`data.md`](../../codemaps/data.md) 의 ERD 에는 `diff`·`analysis`·
`contribution_rules` 같은 **대용량 텍스트 컬럼**이 있다. 여기서 양쪽이 갈라지면 그때 정한다 —
벤더별 분리로 갈지, H2 를 버리고 Testcontainers PostgreSQL 단일로 갈지(Q-9 와 함께).

**검증 (2026-09-21)** — H2·PostgreSQL 양쪽에서 V1 적용 + `validate` 통과를 실제 기동으로 확인했다.

### Q-2b. 🟢 「H2 에서 됐다」의 간극 — **Testcontainers 로 메웠다** (2026-09-22 · #5)

대용량 텍스트를 `TEXT` + `@Column(columnDefinition = "TEXT")` 로 두고 **양쪽에서 실제로 확인**했다.
H2 는 `./gradlew build`(Flyway 적용 + `validate`), PostgreSQL 은 `SchemaMigrationTest`(Testcontainers).
벤더 분리도 H2 제거도 하지 않았고, 단일 SQL 한 벌이 양쪽에서 돈다.

⚠️ `@Lob` 은 쓰지 않는다 — PostgreSQL 에서 `oid` 로 매핑돼 라지오브젝트 테이블을 따로 쓰게 된다.

**남은 위험** — 아래 절은 여전히 유효하다. `TEXT` 는 통과했지만 JSONB·파티셔닝은 시도하지 않았다.

### Q-2b-1. 🟡 벤더 고유 문법은 여전히 금지다

위 결정의 잔여 위험이다. [`setup.md`](../../docs/setup.md) 가 「H2 에서 됐다고 PostgreSQL 에서
된다고 보지 않는다」고 적어 둔 그 위험을 **없애지 않고 안고 가기로** 한 것이다.

지금은 사람이 수동으로 양쪽을 돌려 확인한다. CI(Q-10)가 생기면 **양쪽 검증을 자동화**한다.

---

## 🟡 곧 부딪히는 것

### Q-3. 실행 프로필 분리 시점

PRD §6.2 는 API 와 Worker 를 나눠 그렸지만, PRD §21 은 「MVP 는 Scheduler + DB」다.
지금은 단일 프로세스다. **언제 `web`/`worker` 프로필로 가르나.**

- 가르기 전까지: 스캔·코딩 작업이 API 스레드를 점유한다. 코딩 1건이 30분(`timeout-seconds: 1800`)이다
- 판단 기준 후보 — 동시 실행 2건 이상이 필요해질 때 / API 응답 지연이 관측될 때

### Q-4. 샌드박스 의존성 해석과 네트워크

S-3 은 `SANDBOX_NETWORK=none` 을 요구하는데, Gradle/Maven 빌드는 의존성을 받아야 한다. 모순을 어떻게 푸나.

| 선택지 | 비고 |
|---|---|
| 워밍 단계만 네트워크 개방 후 차단 | 2단계 실행. 현재 `.env.example` 이 전제하는 방식 |
| 사내/로컬 미러 프록시 경유 | 네트워크는 열되 대상이 고정 |
| 의존성 캐시 볼륨 사전 구성 | 저장소마다 다른 의존성은 못 덮는다 |

**걸리는 것** — `agent` 도메인의 샌드박스 능력 인터페이스 시그니처.

### Q-5. 「사람이 고른다」의 실제 UI

S-6 은 `SELECTED` 전이가 사람의 명시적 행위라고 했는데, PRD §23 의 API 목록에는 **후보 선택 엔드포인트가 없다**
(`analyze`·`implement`·`verify`·`pull-request` 만 있다).

- `POST /candidates/{id}/implement` 호출 자체를 선택 행위로 볼 것인가
- 아니면 별도 `select` 를 둘 것인가 — 상태머신에 `SELECTED` 가 있으니 무언가는 그 전이를 일으켜야 한다

### Q-6. 재시도 3회의 단위

`agent.execution.max-retries: 3` 이 무엇의 3회인가. PRD §17 다이어그램은 「구현→테스트」 루프를 그렸다.

- 테스트 실패 3회인가, 리뷰 실패까지 합산 3회인가
- 단계별 독립 카운터인가 후보 전체 통합인가

**걸리는 것** — `AgentRun.attempt` 의 의미. 정의가 흔들리면 비용 집계도 흔들린다.

---

## 🔵 기록해 둘 것

### Q-7. ✅ Lombok — **도입** (2026-09-22 · #5)

엔티티가 1개에서 7개가 되면서 보일러플레이트가 실제 비용이 됐다. 「빌드 문제 표면이 넓어진다」는
원래 보류 사유는 유효하지만, 어노테이션 프로세서 하나를 감수할 만큼 반복이 커졌다.

**엔티티에서 허용하는 것은 둘뿐이다.**

| 허용 | 금지 | 왜 |
|---|---|---|
| `@Getter` | `@Setter` · `@Data` | public setter 가 생기면 **상태머신 불변식이 우회 가능**해진다 (S-6) |
| `@NoArgsConstructor(access = PROTECTED)` | `@Builder` · `@AllArgsConstructor` | 생성 경로가 늘면 「draft 아닌 PR」 같은 불법 상태를 만들 수 있다 (S-2) |
| | `@EqualsAndHashCode` | JPA 프록시와 충돌한다 |

### Q-7b. 🔵 Lombok 사용 범위를 엔티티 밖으로 넓힐 것인가

지금은 엔티티에만 쓴다. UseCase·어댑터·DTO 에는 쓰지 않았다 — record 로 충분하다.
넓히려면 그때 판단한다.

### Q-8. AI 기여를 금지하는 저장소 판정

S-5 가 요구하는 판정인데, **표준 표기법이 없다.** `AGENTS.md`·`CONTRIBUTING.md` 의 자연어를 LLM 으로 읽어야 한다.
판정 실패 시 「보류」로 두기로 했지만, 보류가 많아지면 파이프라인이 멈춘다. 임계 정책 미정.

### Q-9. 테스트에서 GitHub·LLM 을 무엇으로 대체하나

WireMock · 자체 페이크 · 녹화 응답 중 미정. 대외 호출을 타는 통합 테스트를 CI 에 둘 수는 없다.

> **DB 는 Q-9 의 범위가 아니다.** PostgreSQL 은 Testcontainers 로 간다고
> [`testing-philosophy.md`](../conventions/testing-philosophy.md) 가 이미 정해 뒀고,
> #5 에서 실제로 배선했다. 여기 남은 것은 **GitHub·LLM 대역**뿐이다.

### Q-9b. ⚠️ docker-java 가 API 버전을 협상하지 않는다 — 함정 기록 (2026-09-22 · #5)

Testcontainers 를 붙일 때 **Docker 가 정상인데도** 다음 오류로 막힌다.

```
Could not find a valid Docker environment. Please see logs and check configuration
```

Docker 가 안 떠 있는 것처럼 읽히지만 **실제 원인은 API 버전 거부**다. docker-java 가
기본값 v1.32 로 요청하고 최신 엔진이 이를 **400** 으로 돌려보낸다(이 엔진은 v1.41+ 만 받는다).

| 시도 | 결과 |
|---|---|
| `DOCKER_HOST` · `DOCKER_API_VERSION` **환경변수** | ❌ 안 먹는다 |
| `~/.testcontainers.properties` 전략 핀 제거 | ❌ 무관 (별개 문제였다) |
| **시스템 프로퍼티 `api.version`** | ✅ **해결** |

docker-java 는 환경변수가 아니라 **점 표기 시스템 프로퍼티**를 읽는다.
`build.gradle.kts` 의 Test 태스크에 `systemProperty("api.version", "1.44")` 로 걸어 뒀다.

진단에 오래 걸리는 자리다. 증상만 보고 「Docker 문제」로 넘기지 않는다.

### Q-10. CI 부재

GitHub Actions 워크플로가 없다. 지금 유일한 게이트는 **로컬 `./gradlew build`** 이고,
`.claude/scripts/impl-test-loop.sh` · `pre-commit-check.sh` 가 그것을 대신 돌린다.
CI 를 만들면 두 훅의 역할을 재정의해야 한다.

---

## 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-18 | smileboy0014 | 초안 — PRD v1.1 대비 미결 10건 |
