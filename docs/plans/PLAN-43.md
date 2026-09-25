# PLAN-43: `@SpringBootTest` 우회 차단

**이슈**: [#43](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/43)
**type**: fix
**작성일**: 2026-09-25
**작성자**: smileboy0014

> `fix` 프로필이라 약식이다 — **원인 · 수정 · 검증** 3절.

---

## 1. 원인

### 1-1. 가드는 자기 컨텍스트만 본다

#4 가 세운 `ExternalAdapterIsolationTest` 는 **자기가 띄운 컨텍스트**에 실어댑터 빈이 없음을
단언한다. 다른 테스트가 어떤 컨텍스트를 띄우는지는 알지 못한다.

배선은 **프로필 하나로** 갈린다.

| | `@AgentIntegrationTest` (`test` 프로필) | raw `@SpringBootTest` (기본 프로필) |
|---|---|---|
| `@ExternalAdapter` = `@Profile("!test")` | ❌ 빠짐 | ✅ **올라옴** |
| `@FakeAdapter` = `@Profile("test")` | ✅ 뜸 | ❌ **안 뜸** |

즉 우회의 증상은 「대역이 조용히 빠지는」 것이 아니라 **「실물이 들어오는」** 것이다.

### 1-2. 실물 사례가 이미 `main` 에 있다

`SchemaMigrationTest.java:35` 가 raw `@SpringBootTest` 다. 그 컨텍스트에는 지금
**진짜 `GitHubApiClient` · `GitHubIssueSource` · `GitHubRepositorySource` 가 올라와 있다.**

**오늘은 무해하다** — 생성만 되고 호출되지 않으며 `GITHUB_TOKEN` 도 비어 있다.
위험해지는 시점은 **#22 · #23 의 Fork push 어댑터**가 생길 때다. 같은 컨텍스트에 쓰기
어댑터가 올라오고, 그것이 S-1 이 지키는 지점이다.

### 1-3. #4 가 이것을 남긴 이유 — 그리고 전제가 바뀐 지점

#4 초반의 `@AgentIntegrationTest` 는 `FakeExternalDependencies` 를 `@Import` 하는 구조였다.
그래서 「실 DB 검증(`SchemaMigrationTest`)에 페이크 조립을 묶지 말자」가 성립했고,
그 테스트를 **의도적 예외**로 뒀다.

**지금은 프로필만 켜는 구조다.** 조립할 것이 없으므로 그 이유가 사라졌다 —
스키마 검증에 진짜 GitHub 어댑터가 필요할 이유가 없다.

예외가 사라지면 **허용 목록 없는 단순한 스캔**이 가능해진다. 순서가 중요하다 —
이관을 먼저 하고 스캔을 넣는다.

## 2. 수정

| # | 무엇 | 파일 |
|---|---|---|
| 1 | `SchemaMigrationTest` 를 `@AgentIntegrationTest` 로 이관 → **raw 사용 0** | `SchemaMigrationTest.java` |
| 2 | **컨텍스트를 띄우는 모든 클래스에 `test` 프로필**이 있음을 단언 | `IntegrationTestProfileTest.java` (신규) |
| 2b | 검사기 물림을 회귀로 고정하는 상시 표본 | `probe/ProbeUnprofiledBootstrap.java` · `probe/BypassProbe.java` (신규) |
| 3 | 「열려 있는 구멍」 절을 **장치 4(프로필 불변식)**로 갱신 · 남는 구멍 명시 | `testing-philosophy.md` |
| 4 | `@AgentIntegrationTest` javadoc 의 구멍 설명 갱신 | `AgentIntegrationTest.java` |

### ⚠️ 2번 — 「직접 사용 금지」로 짰다가 우회를 재현하고 바꿨다

초안은 `AnnotationTypeFilter(SpringBootTest.class, considerMetaAnnotations=false)` 로
**직접 선언 0건**을 단언하는 것이었다. 실제로 미끼를 만들어 돌려 보니 **셋이 빠져나갔다.**

| 우회 | 왜 빠지나 |
|---|---|
| `@SpringBootTest` 를 메타 애노테이트한 **새 애노테이션** | `considerMetaAnnotations=false` 의 대가. 켜면 준수 클래스가 전부 오탐된다 |
| 추상 상위 클래스 **2단계 이상** 상속 | `AnnotationTypeFilter` 가 조부모까지 따라가지 않는다 |
| `@ContextConfiguration` · `@DataJpaTest` 등 | 필터가 `@SpringBootTest` 한 타입만 본다 |

**축을 바꿨다** — 「어떻게 띄웠는가」가 아니라 **「프로필이 맞는가」**를 본다.
후보를 애노테이션으로 거르지 않고 전부 훑은 뒤 `@BootstrapWith` 또는 `@ContextConfiguration`
으로 판정하고, `SearchStrategy.TYPE_HIERARCHY` 로 상위까지 읽는다. 셋이 한 번에 닫힌다.

⚠️ **물림을 회귀로 고정한다.** 위반이 0건이면 검사기가 아무것도 못 잡아도 초록이다.
`probe` 패키지에 상시 양성 표본을 두되, **이름이 `Test` 로 끝나지 않고 `@Test` 메서드도 없어
실행되지 않는** 형태로 만든다 — 미끼 때문에 실어댑터가 올라오면 본말전도다.

### 판단 — `@SchemaTest` 같은 별도 애노테이션을 만들지 않는다

`SchemaMigrationTest` 가 `@AgentIntegrationTest` 를 쓰면 페이크가 함께 올라온다.
스키마 검증에 무해하고(쓰지 않는다), 애노테이션을 둘로 가르면 「어느 것을 쓸지」라는
새 판단을 만든다. **진입점은 하나로 유지한다.**

---

## 3. 검증

| # | 무엇 | 어떻게 |
|---|---|---|
| 1 | 스캔이 **현재 위반을 실제로 잡는다** | 이관 **전에** 스캔을 돌려 `SchemaMigrationTest` 가 적발되는지 확인 |
| 2 | 스캔이 **준수 클래스를 오탐하지 않는다** | `@AgentIntegrationTest` 사용 클래스가 목록에 없어야 한다 |
| 3 | 스캔이 **0건 검사로 초록이 되지 않는다** | 스캔 대상 테스트 클래스 수가 0 이 아님을 함께 단언 |
| 4 | 이관 후 스키마 검증이 그대로 통과 | `SchemaMigrationTest` 5건 green |
| 5 | 전체 게이트 | `./gradlew build` — `exit=0` + `BUILD SUCCESSFUL` **양쪽** |

⚠️ 1번이 이 이슈의 핵심 검증이다. **고치기 전에 잡히는 것을 먼저 보는 것**이
「스캔이 실제로 문다」의 유일한 증거다 — 이관부터 하면 스캔이 처음부터 초록이라
무는지 알 수 없다.

---

## 4. 게이트 판정

### 안전 경계

| 조항 | 접촉 | 어떻게 |
|---|---|---|
| **S-1 · S-2** | ✅ **간접** | 이 수정이 닫는 구멍이 그것이다. 쓰기 어댑터(#22·#23) 이후 raw `@SpringBootTest` 컨텍스트에 실물이 올라오면 대상 저장소로 나가는 호출 경로가 테스트 안에 생긴다 |
| S-3 · S-4 · S-5 · S-6 | — | 샌드박스 실행·시크릿·대상 저장소 산출물·상태 전이를 건드리지 않는다 |

### 미결

| 항목 | 처리 |
|---|---|
| **Q-3** 프로필 분리 | **닫지 않는다.** `test` 는 `web`/`worker` 와 **직교**하는 축이라 선택지를 줄이지 않는다 |
| Q-9 · Q-9b | 닫혀 있다. 건드리지 않는다 |
| Q-10 | 닫혀 있다(#27). 건드리지 않는다 |

**닫는 미결 없음. 새로 여는 미결도 없음.**

---

## 5. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-25 | smileboy0014 | 초안 — #4 가 남긴 구멍 2건 |
| 2026-09-25 | smileboy0014 | **검사 축을 「직접 사용 금지」→「프로필 불변식」으로 교체** — 미끼로 우회 3종을 재현해 초안이 뚫리는 것을 확인했다. 상시 양성 표본으로 물림을 회귀 고정. 문서의 「우회는 막혀 있다」 단언도 남는 구멍을 적는 형태로 정정 |
| 2026-09-25 | smileboy0014 | **Java 25 함정 기록을 범위에서 제외** — Java 21 고정은 #4 에서 이미 끝났고, 문서 기록은 지금 필요하지 않다고 판단 |
