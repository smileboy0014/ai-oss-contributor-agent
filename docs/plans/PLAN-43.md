# PLAN-43: `@SpringBootTest` 우회 차단 + Java 25 함정 기록

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

### 1-4. Java 25 함정은 고쳤으나 기록이 없다

#4 가 `gradle/gradle-daemon-jvm.properties` 로 고쳤다. 남은 것은 **기록**뿐인데,
증상이 원인을 전혀 가리키지 않아 기록 가치가 크다.

```
* What went wrong:
  25.0.4.1
```

⚠️ 헷갈리는 핵심은 **축이 둘**이라는 것이다 — `build.gradle.kts` 의 `toolchain` 은
**컴파일·테스트가 쓸 JVM** 을 정하고, **데몬이 어느 JVM 에서 도는지는 정하지 않는다.**
그래서 toolchain 을 21 로 박아 둬도 런처 JVM 이 25 면 그대로 죽는다.

---

## 2. 수정

| # | 무엇 | 파일 |
|---|---|---|
| 1 | `SchemaMigrationTest` 를 `@AgentIntegrationTest` 로 이관 → **raw 사용 0** | `SchemaMigrationTest.java` |
| 2 | raw `@SpringBootTest` 사용 **0건**을 단언하는 정적 스캔 | `SpringBootTestUsageTest.java` (신규) |
| 3 | 「열려 있는 구멍」 절을 **닫힌 것으로** 갱신 | `testing-philosophy.md:156` |
| 4 | `@AgentIntegrationTest` javadoc 의 구멍 설명 갱신 | `AgentIntegrationTest.java` |
| 5 | Java 25 함정을 Q-9b 옆에 기록 | `open-questions.md` |

### ⚠️ 2번의 구현 함정 — #4 에서 미리 확인한 것

`ClassPathScanningCandidateComponentProvider` + `AnnotationTypeFilter(SpringBootTest.class)` 는
**기본적으로 메타 애노테이션을 따라간다.** 그대로 두면 `@AgentIntegrationTest` 를 **제대로 쓴**
클래스까지 전부 적발한다.

- `AnnotationTypeFilter(SpringBootTest.class, /* considerMetaAnnotations */ false, ...)`
- `@SpringBootTest` 가 `@Inherited` 인 점도 오탐 요인 → **선언된 애노테이션**으로 확인한다

스캐너가 「직접 선언」과 「메타를 통한 선언」을 구분하지 못하면 이 수정 자체가 성립하지 않는다.
**그 구분이 이 테스트의 핵심**이므로 양쪽을 모두 테스트로 고정한다.

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
| Q-9 · Q-9b | 닫혀 있다. Q-9b 옆에 **함정을 한 건 추가**할 뿐 판단을 바꾸지 않는다 |
| Q-10 | 닫혀 있다(#27). CI 가 같은 `gradle-daemon-jvm.properties` 로 러너 JVM 을 고정한다는 점만 인용 |

**닫는 미결 없음. 새로 여는 미결도 없음.**

---

## 5. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-25 | smileboy0014 | 초안 — #4 가 남긴 구멍 2건 |
