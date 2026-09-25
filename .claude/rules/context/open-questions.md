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

### Q-6. ✅ 재시도 3회의 단위 — **「구현→검증→리뷰」 루프 1바퀴가 1회** (2026-09-25 · #35 논의)

PRD §17 다이어그램이 이미 절반을 답해 두고 있었다. 그림을 그대로 읽으면 된다.

```
Implementation ──> Test ──PASS──> AI Review ──PASS──> Ready for PR
      ↑             │FAIL             │FAIL
      │             ↓                 │
      └── Yes ── {Retry<3?} <─ Error Analyzer <┘
                    │No
                    ↓  FAILED
```

| 물음 | 답 | 근거 |
|---|---|---|
| 테스트 실패 3회인가, 리뷰 실패까지 합산 3회인가 | **합산** | 리뷰 실패가 테스트 실패와 **같은** Error Analyzer 로 들어가고, 게이트가 하나뿐이다 |
| 단계별 독립인가 후보 전체 통합인가 | **루프 통합** | 카운터의 주체는 stage 가 아니라 **사이클**이다 |
| `ANALYZE`·`PLAN` 은 어디 속하나 | **카운터 밖** | 다이어그램 안에 없다. 루프가 아니라 선형 단계다 |

**확정**

| 항목 | 값 |
|---|---|
| 세는 단위 | `CODE` → `VERIFY` → `REVIEW` **한 바퀴 = `attempt` 1** |
| 상한 | 3 — `agent.execution.max-retries` |
| 소진하면 | 후보가 `FAILED`. 그 자체가 **사람에게 넘기는 신호**다 (S-6) |
| `AgentRun.attempt` | 그 사이클 번호. **같은 사이클의 3행이 같은 값을 갖는다** |
| `ANALYZE`·`PLAN` | 파이프라인 재시도 없음. 실패는 즉시 `FAILED` |

⚠️ 앞 단계를 재시도하지 않는 것이 「한 번에 성공해야 한다」는 뜻은 아니다.
**전송 계층 재시도가 이미 별개 축으로 흡수**한다 — 5xx·타임아웃은 `github.max-retries`
(그리고 #10 의 LLM 어댑터)에서 처리되고 파이프라인 카운터를 태우지 않는다.
두 축의 구분은 [`../conventions/architecture.md`](../conventions/architecture.md) §4.

⚠️ **곱셈 예산** — 후보 1건당 대외 호출 최대 **3 × (1 + 2) = 9회**(첫 시도 + 재시도 2).
전송 상한을 올릴 때는 이 곱을 먼저 계산한다.

**단계별 독립 카운터를 택하지 않은 이유** — 최악 5 × 3 = 15회 실행으로 비용이 터지고,
`CODE` 실패로 되돌아가면 `VERIFY` 도 다시 도는 구조라 「단계별」이 애초에 루프를 표현하지 못한다.
**후보 전체 통합도 아니다** — `ANALYZE` 에서 2번 타면 정작 고쳐야 할 루프에 1회밖에 남지 않아
PRD §17 의 「3번 고쳐본다」는 의도가 지워진다.

**남은 것** — 불변식 ⑧(재시도 상한)을 후보 루트가 **어떤 필드로** 들고 있을 것인가.
`attempt` 의 의미가 정해졌으므로 이제 정할 수 있다 — #21 · #12.

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

### Q-8. ✅ AI 기여를 금지하는 저장소 판정 — **「못 읽음」과 「읽었는데 금지 없음」을 가른다** (2026-09-25 · #7)

#### 먼저 실측했다 (2026-09-25)

탁상공론이 되지 않도록 대상 저장소 7곳의 실제 파일을 확인했다.

| 저장소 | `AGENTS.md` | `CLAUDE.md` | 기여 문서 |
|---|---|---|---|
| **spring-kafka** (Phase 1) | ✅ | ✅ | `CONTRIBUTING.md` |
| spring-framework | ❌ | ❌ | `CONTRIBUTING.md` |
| spring-boot | ❌ | ❌ | **`CONTRIBUTING.adoc`** |
| spring-data-redis | ❌ | ❌ | **`CONTRIBUTING.adoc`** |
| reactor-core | ❌ | ❌ | **없음** |
| apache/kafka | ✅ | ❌ | `CONTRIBUTING.md` |
| redis/redis | ❌ | ❌ | `CONTRIBUTING.md` |

**Phase 1 대상의 내용이 결정적이었다.** `spring-kafka` 의 `AGENTS.md`·`CLAUDE.md` 는
각각 **한 줄**이고 「`CONTRIBUTING.md` 를 보라」가 전부다. 그 `CONTRIBUTING.md` 301줄에
**AI·LLM·generated·bot 언급이 하나도 없다.**

즉 **명시적 금지도 명시적 허용도 없다.** 조사한 7곳 중 명시적 허용을 적은 곳은 **0개**다.

#### 확정 ① — 금지 문구가 없으면 허용이다

「명시적 허용이 있어야 허용」으로 두면 **Phase 1 의 유일한 대상이 즉시 보류**되고
파이프라인이 한 번도 돌지 않는다. 제품이 성립하지 않는다.

**S-5 가 막으려는 것은 「판정 불가를 통과로 처리」하는 것이지, 「침묵을 금지로 취급」하는 것이 아니다.**
가르는 선은 **읽었는가**다.

| 상황 | 판정 | `aiContributionAllowed` |
|---|---|---|
| 후보 경로를 다 읽었고 금지 문구 없음 | **허용** | `TRUE` |
| 금지 문구를 찾음 | **금지** | `FALSE` |
| 후보 경로가 전부 404 (문서 자체가 없음) | **허용** | `TRUE` — 금지 표기가 존재할 수 없다 |
| **하나라도 못 읽음** (5xx · 레이트리밋 · 1MB 초과 · 파싱 실패) | 🔴 **보류** | `NULL` |
| LLM 이 판정하지 못함 | 🔴 **보류** | `NULL` |

이 구분은 새로 만드는 것이 아니다. **#6 이 이미 계약으로 갈라 뒀다** —
`fetchFile` 의 `Optional.empty()` 는 **404 하나뿐**이고 나머지는 예외다.
1MB 초과는 `GitHubUnreadableContentException` 이다. 그 계약을 그대로 쓴다.

#### 확정 ② — 보류는 사람이 명시적으로 푼다

**자동으로 풀리지 않는다.**

- 재분석은 같은 입력에 같은 결과다. 문서가 바뀔 때만 달라지는데, 보류의 주된 원인
  (못 읽음)은 문서 변경과 무관하다
- 시간 경과로 풀어 주면 S-5 가 무너진다. 「기다리면 통과」는 게이트가 아니다
- 횟수 소진 후 영구 배제도 하지 않는다 — 일시적 장애로 배제된 저장소가 조용히 사라진다

보류 상태를 그대로 두고 **왜 보류됐는지**를 기록해 사람이 보고 판단한다.
해소 API 는 승인 지점과 함께 만든다 — Q-5 · #24.

#### ⚠️ 확장자 변종을 빠뜨리면 S-5 구멍이 된다

위 표가 보여주듯 `.md` 만 찾으면 spring-boot·spring-data-redis 에서 404 를 받는다.
**규약이 있는데 못 읽은 것을 「규약 없음 → 허용」으로 번역**하게 된다 — 확정 ①이 가장
위험해지는 자리다.

후보 경로에 최소한 다음을 포함한다. `AGENTS.md` 가 `CONTRIBUTING` 을 가리키는 경우가
실제로 있으므로(spring-kafka) **한 파일만 읽고 끝내지 않는다.**

```
AGENTS.md · CLAUDE.md
CONTRIBUTING.md · CONTRIBUTING.adoc · CONTRIBUTING.rst · CONTRIBUTING
.github/CONTRIBUTING.md · .github/CONTRIBUTING.adoc
```

#### 임계 정책은 지금 정하지 않는다

원래 이 항목이 걱정한 「보류가 많아지면 파이프라인이 멈춘다」는 **Phase 1 에서 성립하지 않는다.**
대상이 `spring-kafka` **하나**이고 `RepositoryPolicy` 는 저장소당 1건이다. 보류는 최대 1건이다.

저장소가 늘어나는 Phase 2 에서 실제 보류율을 보고 정한다. 지금 정하면 데이터 없이 정하는 것이다.

### Q-9. ✅ 테스트에서 GitHub·LLM 을 무엇으로 대체하나 — **3계층으로 확정** (2026-09-25 · #4)

「WireMock 이냐 페이크냐」가 잘못된 물음이었다. **층마다 보는 것이 다르다.** 하나로 통일하려는 것이 문제였다.

| 층 | 대역 | 무엇을 보나 |
|---|---|---|
| 능력 소비자 (UseCase) | **자체 페이크** — `FakeIssueSource` 류 | 계약이 실제로 대체 가능한가 · 업무 흐름 |
| 어댑터 매핑 | **`MockRestServiceServer`** | 요청 조립 · 응답 파싱 · 오류 변환(403 구분) |
| **전송 계약** | **WireMock** | 읽기 타임아웃 · 리다이렉트 거부 · 연결 실패 |

**WireMock 을 세 번째 층에만 쓰는 이유** — `MockRestServiceServer` 는 `ClientHttpRequestFactory` 를
통째로 갈아끼운다. **JDK `HttpClient` 가 아예 돌지 않는다.** 그래서 `read-timeout`·`Redirect.NEVER`
같은 전송 설정은 그 층에서 **검증할 수단 자체가 없다.**
[`external-deps.md`](./external-deps.md) 가 「기본값에 맡기면 무한 대기가 생긴다」를 규율로 박아 뒀는데,
그 규율이 지켜지는지 확인할 방법이 없는 상태였다 — #6 의 「읽기 타임아웃 미검증」이 이것이다.

소켓이 필요한 것만 올린다. 클래스당 1~2건이면 충분하고, 세 도구가 서로 다른 것을 보므로 중복이 아니다.

| 대상 | 적용 |
|---|---|
| GitHub API | 3계층 전부 |
| LLM API | 3계층 전부 — #10 |
| **Docker 샌드박스** | **페이크만.** HTTP 가 아니라 전송 계약 층이 성립하지 않는다 |

**녹화 응답(VCR)은 채택하지 않는다.** 녹화하려면 토큰으로 실 API 를 한 번은 타야 하고,
녹음본에 토큰·PII 가 섞이면 **저장소에 그대로 커밋된다** — S-4. 스키마 충실도 이득보다 위험이 크다.

**페이크 위치·명명** — 능력 인터페이스와 **같은 패키지**의 `src/test`, 이름은 `Fake{능력이름}`
(`repository/domain/FakeRepositorySource`). #6 이 만든 관행을 그대로 규약으로 굳힌다.

**남은 것** — `@SpringBootTest` 에서 **실제 어댑터가 올라오지 않는 것**을 무엇으로 보장할지는
아직 정하지 않았다. 지금은 대외 호출을 타는 `@SpringBootTest` 자체가 없어 드러나지 않는다 — #4 에서 마무리한다.

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

### Q-10. ✅ CI 부재 — **지금 만든다. 훅에는 빠른 검사만 남긴다** (2026-09-25 · #27)

게이트를 로컬에만 두는 것이 더는 성립하지 않는다.

- 커밋 훅이 **git 훅으로 이관**되면서 `--no-verify` 로 우회 가능해진다. 우회를 잡을 곳이 로컬 밖에 없다
  ([`../conventions/commit-convention.md`](../conventions/commit-convention.md))
- Q-9 로 WireMock 이 들어오고 Testcontainers 도 이미 있다. **스위트가 무거워지는 방향**이라,
  커밋마다 전부 도는 구조는 「시간 없어서 스킵」을 부른다

**역할 분담**

| 어디 | 무엇 | 왜 |
|---|---|---|
| git `pre-commit` | `secret-scan.sh` · `safety-boundary-check.sh` | 초 단위. **유출은 커밋 전에** 막아야 회수가 가능하다 |
| **CI** (GitHub Actions) | `./gradlew build` + 위 스캔 2종 **재실행** | 진짜 게이트. `--no-verify` 우회가 여기서 잡힌다 |
| Stop 훅 `impl-test-loop.sh` | 변경분 있으면 테스트 | 로컬 피드백은 이쪽이 준다 |

`pre-commit-check.sh`(`./gradlew check`)는 **훅에서 뺀다.** CI 가 같은 일을 하고,
커밋마다 스위트 전체를 기다릴 이유가 없다. CI 는 `./gradlew build` 를 직접 부르고,
스크립트는 **수동 실행용**으로 남긴다.

⚠️ `build.gradle.kts` 의 `systemProperty("api.version", "1.44")` 는 로컬 Docker 때문에 박힌 핀이다(Q-9b).
**러너 엔진이 그보다 낮으면 Testcontainers 가 깨진다** — CI 구성 시 러너의 Docker API 버전을 확인한다.

**남은 것** — branch protection(필수 체크)은 걸지 않았다. 1인 개발이라 자기발목이 될 수 있어,
CI 가 안정적으로 green 을 내는 것을 확인한 뒤 판단한다.

---

## 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-18 | smileboy0014 | 초안 — PRD v1.1 대비 미결 10건 |
| 2026-09-25 | smileboy0014 | Q-6·Q-9·Q-10 확정 — #35 리뷰에서 드러난 미결 일괄 정리 |
| 2026-09-25 | smileboy0014 | Q-8 확정 — 대상 저장소 7곳 실측 후 판정 규칙 확정 |
