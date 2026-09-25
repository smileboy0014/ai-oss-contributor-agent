# 패키지 구조

> 규율의 근거와 체크리스트는 [`rules/conventions/architecture.md`](../rules/conventions/architecture.md).
> 여기는 **어디에 무엇이 있는지**만 본다.

## 전체

```
ai-oss-contributor-agent/
├── build.gradle.kts            단일 프로젝트 빌드
├── settings.gradle.kts
├── gradle/libs.versions.toml   버전 단일 관리 — 버전은 여기서만 올린다
├── gradlew                     Gradle 8.14.3 래퍼
├── docker-compose.yml          postgres · redis
├── .env.example                환경변수 예시
├── docs/                       PRD 등 긴 산출물 (세션에 자동으로 실리지 않음)
├── .claude/                    이 디렉토리
└── src/
    ├── main/java/com/ossagent/
    ├── main/resources/application.yml
    └── test/java/com/ossagent/
```

## 소스 — 도메인 5 + 공통 2

```
com.ossagent
├── OssContributorAgentApplication
├── config/                     조립 전용. 비즈니스 코드 금지 (ClockConfig)
├── support/                    도메인 없는 공통
│   └── web/                    ApiExceptionHandler — HTTP 매핑은 여기 한 곳
├── repository/                 대상 저장소 등록 · 기여 규약 분석
├── issue/                      이슈 수집 · 필터            (비어 있음)
├── candidate/                  기여 후보 · 상태머신
├── agent/                      LLM · 샌드박스 실행         (비어 있음)
└── pullrequest/                Fork · Draft PR            (비어 있음)
```

비어 있는 도메인에는 `package-info.java` 만 있다. **경계를 먼저 그어 둔 것**이다 —
나중에 `candidate` 안에 수집 로직이, `agent` 안에 PR 생성이 섞여 들어가는 것을 구조로 막는다.

## 도메인 내부 — 헥사고날 라이트

```
com.ossagent.{도메인}
├── domain/          모델(=JPA 엔티티) · 상태머신 · 불변식 · 능력 인터페이스 · 도메인 예외
├── application/     XxxUseCase — 트랜잭션 경계
└── adapter/
    ├── in/{web·scheduler·event}/     + dto/
    └── out/{persistence·github·llm·sandbox}/
```

```
adapter/in  ──▶  application  ──▶  domain  ◀──  adapter/out
                                   (중심 · 바깥을 모른다)
```

### 실제 예 — `repository` 도메인

```
repository/
├── domain/
│   ├── OssRepository.java                      엔티티
│   ├── RepositoryNotFoundException.java        도메인 예외 — HTTP 를 모른다
│   └── RepositoryAlreadyRegisteredException.java
├── application/
│   ├── RegisterRepositoryUseCase.java          @Transactional
│   └── RequestScanUseCase.java                 @Transactional + Clock 주입
└── adapter/
    ├── in/web/
    │   ├── RepositoryController.java           변환·위임만
    │   └── dto/  RegisterRepositoryRequest · RepositoryResponse · ScanRequestedResponse
    └── out/persistence/
        └── OssRepositoryRepository.java        Spring Data JPA
```

## 이름 규칙

| 대상 | 규칙 | 예 |
|---|---|---|
| UseCase | `{동사}{대상}UseCase` | `RegisterRepositoryUseCase` |
| 능력 인터페이스 (domain) | **능력 이름** | `IssueSource` · `CodeSandbox` · `DraftPrPublisher` |
| 구현체 (adapter/out) | **기술 이름** + 능력 | `GitHubIssueSource` · `DockerCodeSandbox` |
| Spring Data | `{엔티티}Repository` | `OssRepositoryRepository` |
| 요청/응답 DTO | `{동작}Request` / `{대상}Response` | `RegisterRepositoryRequest` |

⚠️ `client` · `port` 라는 패키지명은 쓰지 않는다. 기술 이름은 `adapter/out` 의 **클래스 이름**에만 나타난다.

⚠️ `OssRepositoryRepository` 가 어색해 보여도 줄이지 않는다. 줄이면 도메인 패키지(`repository`)와
Spring Data 인터페이스가 같은 이름이 되어 더 헷갈린다 — [`glossary.md`](../rules/context/glossary.md).

## 왜 Gradle 멀티모듈이 아닌가

기준 프로젝트(`torder-membership-crm`)는 `modules/{모듈}/{api,core}` 로 갈라
**남의 모듈 import 를 컴파일 에러로 만든다.** 여기는 그렇게 하지 않았다.

- 도메인이 독립 업무가 아니라 **한 워크플로우의 연속된 단계**다
- DB 스키마가 하나다
- 도메인 5개 × (api,core) = Gradle 프로젝트 10개. 지금 규모에서 배선 비용이 강제력보다 크다

승격 조건은 [`architecture.md`](../rules/conventions/architecture.md) 「모듈 승격 기준」에 있다.
그 전까지 경계는 리뷰와 [`safety-boundary-check.sh`](../scripts/safety-boundary-check.sh) 가 지킨다.

## 테스트

```
src/test/java/com/ossagent/{도메인}/...
```

프로덕션 패키지 구조를 따라간다. 대외 의존(GitHub·LLM·샌드박스)은 **대역으로 갈음**한다 —
[`testing-philosophy.md`](../rules/conventions/testing-philosophy.md).

대역은 **층마다 다르다**(Q-9) — 능력은 자체 페이크, 어댑터 매핑은 `MockRestServiceServer`,
전송 계약은 WireMock. 「페이크로 대체」 한 마디로 뭉치면 층이 하나 빠진다.

```
src/test/java/com/ossagent/{도메인}/domain/Fake{능력이름}.java   능력 대역 — 능력과 같은 패키지
src/test/java/com/ossagent/support/testing/                    통합 테스트 하네스 (아래)
src/test/resources/{github,policy,llm}/                        리소스 픽스처
```

`support/testing` 만 프로덕션 구조를 따라가지 않는다. **하네스**이지 어느 도메인의 테스트가 아니다.

| 파일 | 역할 |
|---|---|
| `AgentIntegrationTest` | 통합 테스트의 표준 진입점. **`@SpringBootTest` 를 직접 쓰지 않는다** |
| `FakeExternalDependencies` | 페이크 조립 지점 + 픽스처 규약 |
| `ExternalAdapterIsolationTest` | 컨텍스트에 실어댑터가 없음을 **강제**하는 가드 |
