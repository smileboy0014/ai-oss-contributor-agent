# PLAN-6: GitHub 능력 인터페이스 정의 + 어댑터 골격

**이슈**: [#6](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/6)
**type**: feature
**작성일**: 2026-09-22
**작성자**: smileboy0014
**상태**: 구현 완료 · 계획 검토(격리 서브에이전트) 반영분 포함

## 1. 요구사항

### 배경

파이프라인의 1~3단계(Register · Policy Analysis · Scan)가 전부 GitHub 읽기에 의존하는데,
지금 GitHub 를 부르는 코드가 **한 줄도 없다**. `POST /api/repositories/{id}/scan` 은 요청 사실만 기록한다.

Q-1 이 2026-09-21 에 **classic PAT + Spring `RestClient` 직접 구현**으로 확정됐으므로 착수를 막는 것이 없다.
이 작업은 그 결정을 **코드의 형태**로 굳히는 자리다. 뒤따르는 #7(규약 수집) · #8(증분 수집)이
여기서 정한 계약 위에 올라간다. 계약이 흔들리면 둘이 같이 흔들린다.

> #15(저장소 분석)는 **여기서 끝나지 않는다.** 키워드 코드 검색(Search API, 30 req/min 별도 리밋)은
> 이 PR 의 두 능력 어디에도 없다. #15 는 세 번째 능력을 추가로 필요로 한다 — 배경에서 미리 약속하지 않는다.

이 PR 이 만드는 것은 **읽기 전용 골격**이다. Fork·push·PR 생성(#22 · #23)은 여기서 만들지 않는다 —
만들지 않는 것 자체가 S-1 의 구조적 방어다.

### 기능 요구사항 (FR)

| # | 요구사항 | 근거 | 결과 |
|---|---------|------|---|
| FR-1 | GitHub 접근을 **능력 이름**으로 `domain` 에 선언 (`RepositorySource` · `IssueSource`). `client`·`port` 패키지명 금지 | 완료조건 ① · 규율 ③ | ✅ |
| FR-2 | 구현은 `adapter/out/github` 에 **기술 이름**으로 | 완료조건 ① | ✅ `GitHubRepositorySource` · `GitHubIssueSource` |
| FR-3 | 공통 HTTP 클라이언트를 `support/github` 에 두고 타임아웃·재시도를 **명시** | 완료조건 ② · codemaps | ✅ connect 5s · read 10s · 재시도 2 |
| FR-4 | `X-RateLimit-Limit`·`-Remaining`·`-Reset` 을 파싱해 **클라이언트 응답에 실어 노출** | 완료조건 ③ | ✅ `GitHubResponse.rateLimit()` — 범위는 아래 주 |
| FR-5 | **403 을 권한 오류와 2차 레이트리밋으로 구분** | 완료조건 ④ · external-deps | ✅ `GitHubErrorTranslator` 단일 지점 |
| FR-6 | 능력 인터페이스의 **페이크 구현** | 완료조건 ⑤ | ✅ `FakeRepositorySource` · `FakeIssueSource` |
| FR-7 | 토큰이 **예외 메시지·로그**에 실리지 않음을 테스트로 고정 | 완료조건 ⑥ · S-4 | ✅ 예외 사슬 · `ListAppender` 로그 · `toString` 3방향 |

> **FR-4 「노출」의 범위 — 의도적으로 `support` 경계에서 멈춘다.**
> 레이트리밋 값은 `GitHubResponse` 에 실려 어댑터가 읽을 수 있다. 그러나 도메인 값
> (`IssuePage` 등)에는 싣지 않는다 — 남은 호출 수는 GitHub 의 개념이고 도메인에 뜻이 없다(규율 ①).
> 「임계 미만이면 지연」이라는 **대응 정책은 이 PR 에 없다.** 어느 작업을 미룰지는 호출자가 알아야 하고,
> 그 호출자가 생기는 것이 #8 이다. 지금은 임계 미만이면 `WARN` 을 남겨 관측 가능하게만 한다.

### 비기능 요구사항 (NFR)

| # | 항목 | 기준 |
|---|------|------|
| NFR-1 | 레이트리밋 | 이 PR 은 API 를 호출하는 **경로를 만들지 않는다**(호출자 없음). 예산 소비 0 |
| NFR-2 | 타임아웃 | connect 5s · read 10s. ⚠ §6 「검증 한계」 참조 |
| NFR-3 | 재시도 | 5xx·I/O 에 한해 최대 2회. 백오프는 **선형 증가**(500ms × 시도 횟수). 레이트리밋·권한은 재시도 안 함 |
| NFR-4 | 비용 | LLM 호출 없음. 해당 없음 |
| NFR-5 | 기동 | 토큰이 비어도 기동 성공(`WARN` 만). **호출 시 인증 헤더를 생략**해 미인증(60 req/h)으로 동작한다 |

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키는가 |
|---|---|---|
| **S-1** 원본 저장소 쓰기 금지 | ✅ | **구조로 막는다, 두 겹으로.** ① `GitHubApiClient` 의 공개 메서드는 `get(...)` 하나뿐 — POST·PATCH·PUT·DELETE 가 **존재하지 않는다**. ② `RestClient` 를 **빈으로 내보내지 않는다** — 노출하면 아무 컴포넌트나 주입받아 `restClient.post()` 를 쓸 수 있어 읽기 전용이 클래스 안에서만 참이 된다. 쓰기 경로가 없으므로 이 PR 에는 어설션을 둘 자리가 없다. 어설션은 쓰기가 생기는 #22 에서 push 직전에 둔다. 테스트 3건으로 고정 — 공개 메서드 목록 · 실제 HTTP 동사가 GET · `@Bean` 반환 타입에 `RestClient` 없음 |
| **S-2** 항상 draft · 자동 머지 금지 | ✅ | PR·리뷰·**이슈 코멘트** API 를 호출하지 않는다. `IssueSource` 는 읽기 전용이고 코멘트 경로가 없다. S-1 의 읽기 전용 표면이 그대로 S-2 도 보장한다 |
| **S-3** 샌드박스 밖 실행 금지 | — | 대상 저장소 코드를 실행하지 않는다. `ProcessBuilder`·`Runtime.exec`·Docker 소켓 없음 |
| **S-4** 시크릿 유출 금지 | ✅ | ① 토큰은 **헤더로만** — URL 에 싣지 않는다(예외 메시지의 URL 이 가장 흔한 유출구다). ② 모든 예외 메시지가 생성자에서 `TokenRedactor` 를 통과한다. ③ `GitHubProperties`·`StaticTokenCredentials` 의 `toString()` 을 **재정의** — record 기본 구현은 토큰을 통째로 찍는다. ④ 대상 저장소 파일 내용·이슈 본문을 로그에 찍지 않는다(`RepositoryFile`·`IssueSnapshot` 의 `toString` 도 재정의). ⑤ 로그에는 우리가 통제하는 식별자만 |
| **S-5** 대상 저장소 규약 우선 | ✅ **(검토로 판정 변경: — → ✅)** | `fetchFile` 의 **실패 모드가 #7 의 보류 판정을 결정한다.** 계약을 못 박았다 — **`Optional.empty()` 는 404 하나뿐**이고 권한·레이트리밋·**1MB 초과로 내용을 못 받음**·디렉터리·심볼릭링크는 전부 예외다. Contents API 는 1MB 를 넘으면 `content` 를 **빈 문자열로** 내려주는데, 이것을 「빈 파일」로 읽으면 규약이 있는데도 「규약 없음 → 허용」이 된다. S-5 는 「파싱 실패는 「허용」이 아니라 「보류」」를 요구하므로 `GitHubUnreadableContentException` 으로 떨어뜨린다. 테스트 5건 |
| **S-6** 승인 지점 우회 금지 | ✅ **(검토로 판정 변경: — → ✅)** | 상태 전이·스케줄러는 건드리지 않는다. **다만 재시도 예산을 하나 신설했다.** 전송 계층 2회는 파이프라인 재시도와 곱해져 **논리적 1회 시도당 GitHub 호출 최대 6회**(3 × 2)가 된다. 상한이 살아 있고(무한 아님) 곱셈 결과가 유한하지만, 재시도 예산을 늘리는 변경이므로 접촉으로 기록한다 |

### 미결 대조 (Q-1 ~ Q-10)

| 항목 | 걸리는가 | 처리 |
|---|---|---|
| **Q-1** GitHub 연동 방식 | ✅ | **확정 사항을 따르고, 「남은 것」도 이행했다.** Q-1 「남은 것」은 「다중 사용자로 확장할 때의 경로는 GitHub App + user-to-server OAuth 다. **능력 인터페이스를 그쪽으로 갈아끼울 수 있게 설계한다 (#6)**」라고 이 이슈를 명시 태그한다. 그 토큰은 **단수명이라 요청마다 갱신**되므로, 값을 빈 생성 시점에 기본 헤더로 구워 넣으면 갈아끼울 자리가 없다. 그래서 값이 아니라 **공급자**(`GitHubCredentials#authorizationHeader()`)를 주입하고 **호출마다 묻는다.** 구현체는 `StaticTokenCredentials` 하나. OAuth 로 가려면 이 인터페이스의 두 번째 구현을 추가하면 된다 |
| Q-2 마이그레이션 도구 | — | 스키마를 건드리지 않는다. 엔티티 없음 |
| Q-3 실행 프로필 분리 | — | 진입점(스케줄러)을 만들지 않는다 |
| Q-4 샌드박스 네트워크 | — | 샌드박스 무관 |
| Q-5 「사람이 고른다」 UI | — | 상태 전이 무관 |
| **Q-6** 재시도 3회의 단위 | ✅ | **가정 ①** — 아래 |
| **Q-7** Lombok | ✅ | #5 가 2026-09-22 에 「도입」으로 닫았다(엔티티 한정 · `@Getter` + `@NoArgsConstructor(PROTECTED)` 만). **이 PR 에는 해당 없다** — 신규 타입이 전부 `record` 이거나 인터페이스라 보일러플레이트가 없다. Lombok 의존을 이 브랜치에서 추가하지 않는다 |
| Q-8 AI 기여 금지 저장소 판정 | — | #7 · #8. 단 S-5 계약(위)이 그 판정의 입력 품질을 보장한다 |
| **Q-9** 대외 의존 테스트 대역 | ✅ | **가정 ②** — 아래 |
| Q-10 CI 부재 | ✅ | 게이트는 로컬 `./gradlew build` 뿐이다. 이 PR 이 CI 를 만들지 않는다(#27) |

**가정** — PR 본문에 같은 내용을 옮긴다.

1. **전송 계층 재시도는 Q-6 이 묻는 「3회」와 다른 축이다.**
   ⚠️ Q-6 은 `agent.execution.max-retries: 3` **이 무엇의 3회인지 자체가 미결**이라고 적어 두었다
   (「단계별 독립 카운터인가 후보 전체 통합인가 / 걸리는 것 — `AgentRun.attempt` 의 의미」).
   여기서 그 답을 정하지 않는다. 다만 **Q-6 의 어느 해석을 택하더라도** HTTP 5xx 한 번이
   파이프라인 카운터를 태우는 것은 PRD §17 의 「구현→테스트 루프」 그림과 맞지 않는다 —
   후보가 코드 문제 없이 `FAILED` 로 떨어진다. 그래서 **별도 키 `github.max-retries`(기본 2)** 를 쓴다.
   - 곱셈 예산: 파이프라인 3 × 전송 2 = **논리적 1회 시도당 GitHub 호출 최대 6회**
   - Q-6 이 「전송 실패도 포함」으로 닫히면 고칠 곳은 `GitHubProperties.maxRetries` 와
     `application.yml` 의 `github.max-retries` **두 곳뿐**이다
   - `.claude/rules/conventions/architecture.md` §4 의 「상한은 `agent.execution.max-retries`」가
     이 두 번째 예산과 어긋나므로 **같은 PR 에서 개정**한다(§9)
2. **페이크는 자체 구현으로 간다** (`FakeRepositorySource`·`FakeIssueSource`, 테스트 소스).
   근거는 추측이 아니라 `testing-philosophy.md` 의 명문 규정 — 「대체 수단은 아직 미정이다(Q-9).
   **정해지기 전까지는 자체 페이크 구현**을 쓴다」.
   - Q-9 가 WireMock·녹화 응답으로 닫히면 교체 범위는 `src/test` 안에 갇힌다. 프로덕션 코드 무영향
   - HTTP 계층(헤더·상태코드·ETag·403 구분)은 페이크로 검증할 수 없어 `MockRestServiceServer`
     (spring-test 내장, 네트워크 없음)를 쓴다. 같은 문서의 「실제 GitHub·LLM·Docker 를 타는
     자동 테스트」 금지에 저촉되지 않는다

## 3. 스코프

| 도메인 | 변경 | 소유 판정 근거 |
|---|---|---|
| `support` | 신규 | HTTP 전송·인증·레이트리밋·에러 변환은 도메인 규칙이 아니다. codemaps 가 `support` 에 「공통 GitHub 클라이언트(토큰·레이트리밋)」를 배정해 두었다 |
| `repository` | 신규 | 저장소 메타데이터·저장소 안의 파일은 「어떤 저장소를 보고 있고 그 규약이 무엇인가」에 속한다 |
| `issue` | 신규 | open 이슈 조회는 「어떤 이슈가 있는가」에 속한다 |
| `config` | 신규 | 빈 조립. 비즈니스 코드 없음 |

**도메인 간 계약**

```java
// com.ossagent.repository.domain
public interface RepositorySource {
    RepositoryMetadata fetchMetadata(RepositoryCoordinates coordinates);
    Optional<RepositoryFile> fetchFile(RepositoryCoordinates coordinates, String path, String ref);
}

// com.ossagent.issue.domain
public interface IssueSource {
    IssuePage fetchOpenIssues(IssueQuery query);
}
```

**`RepositoryCoordinates` 의 소유** — `repository` 도메인이 소유하고 `issue` 가 **값 타입**으로 import 한다.
규율 ④ 가 금지하는 것은 남의 **엔티티·Spring Data 인터페이스** 직접 import 이고,
같은 문장이 「필요한 것은 상대 도메인의 UseCase 또는 **값 타입**」이라고 명시적으로 허용한다.
참조 방향(`repository → issue`)도 codemap 의 단방향 규칙과 일치한다.

**`ref` 를 지금 넣는 이유** — Contents API 는 기본 브랜치를 본다. 특정 시점 파일이 필요해지는 단계
(#15)가 시그니처를 다시 열지 않도록 `null = 기본 브랜치`로 지금 둔다.

## 4. 기술 설계

### 변경 파일 (신규 26 · 수정 1)

**`support/github`**

| 경로 | 내용 |
|------|------|
| `GitHubProperties` | `@ConfigurationProperties("github")`. 🔴 `toString()` 재정의(토큰 마스킹) |
| `GitHubCredentials` · `StaticTokenCredentials` | **자격증명 공급 이음매** — Q-1 「남은 것」(#6) |
| `GitHubRequest` | 읽기 요청 값. **HTTP 메서드 필드가 없다**(GET 전용) |
| `GitHubResponse` | `JsonNode` · etag · **linkHeader** · notModified · `GitHubRateLimit` |
| `GitHubRateLimit` | limit · remaining · resetAt. 「모름」과 「0」을 구분한다 |
| `GitHubHeaders` | 헤더 파싱(패키지 프라이빗). 파싱 실패는 예외가 아니라 「모름」 |
| `GitHubErrorTranslator` | **403 구분의 단일 지점.** `Clock` 주입 |
| `GitHubRetryPolicy` | `shouldRetry` · `backoffFor` — 순수 판정 |
| `GitHubApiClient` | `get(...)` **하나만** 공개. 재시도 루프 · 레이트리밋 경고 |
| 예외 7종 | 아래 표 |

**`support/secret`** — `TokenRedactor` (토큰 패턴 스크럽 · 헤더 값 마스킹)

**`repository`** — `RepositoryCoordinates` · `RepositoryMetadata` · `RepositoryFile` ·
`RepositorySource`(domain) / `GitHubRepositorySource`(adapter/out)

**`issue`** — `IssueSnapshot` · `IssueQuery` · `IssuePage` · `IssueSource`(domain) /
`GitHubIssueSource`(adapter/out)

**`config`** — `GitHubClientConfig` (전송 설정만. `RestClient` 를 빈으로 내보내지 않는다)

**수정** — `application.yml` 에 `github:` 블록

**예외 7종** — 403 구분이 타입으로 드러나야 호출자가 분기할 수 있다.

| 예외 | 조건 | 재시도 |
|---|---|---|
| `GitHubAuthenticationException` | 401 | ❌ |
| `GitHubPermissionException` | 403 **이면서** 레이트리밋 신호 없음 | ❌ |
| `GitHubRateLimitException` | 403/429 + `Remaining: 0`(1차) 또는 `Retry-After`·본문 문구(2차) | ❌ **지연**(정책은 #8) |
| `GitHubResourceNotFoundException` | 404 | ❌ |
| `GitHubUnreadableContentException` | 200 인데 내용을 쓸 수 없음 — **S-5 의 「보류」 신호** | ❌ |
| `GitHubTransientException` | 5xx · I/O · 타임아웃 | ✅ |
| `GitHubApiException` | 그 외 + 위 6종의 부모. 🔴 생성자가 `TokenRedactor` 를 통과시킨다 | ❌ |

### 체크리스트 답변

| # | 항목 | 답 |
|---|------|---|
| 1 | 소유 도메인 | §3 표 |
| 2 | 레이어 배치 | 결정 트리 Q5 → `adapter/out`. 능력 선언만 `domain` |
| 3 | 능력 인터페이스 | **필요** — `RepositorySource`·`IssueSource`. `ForkRegistry`·`DraftPrPublisher` 는 #22·#23 |
| 4 | 🔴 트랜잭션 안 대외 호출 없음 | ✅ **이 PR 에 `@Transactional` 이 하나도 없다.** UseCase 를 만들지 않으므로 트랜잭션 경계가 생기지 않는다 |
| 5 | 상태 전이 영향 | 해당 없음 |
| 6 | 멱등성 | 해당 없음 — 읽기 전용이라 부수효과가 없다. 스캔 멱등성은 #8 |
| 7 | `Clock` 주입 | ✅ `GitHubErrorTranslator`(`Retry-After` HTTP-date → Duration) · `GitHubApiClient`(레이턴시). `Instant.now()` 직접 호출 없음 |
| 8 | 🔴 안전 경계 | §2 — S-1 · S-2 · S-4 · S-5 · S-6 접촉 |

### 데이터 모델 · API 계약

**둘 다 해당 없음** — 엔티티·마이그레이션·HTTP 엔드포인트를 추가하지 않는다.
`IssueSnapshot` 등은 JPA 엔티티가 아니라 값 타입(record)이다.

### 설정 키

```yaml
github:
  base-url: https://api.github.com
  token: ${GITHUB_TOKEN:}
  connect-timeout: 5s
  read-timeout: 10s
  max-retries: 2          # 전송 계층 — agent.execution.max-retries 와 다른 축 (가정 ①)
  retry-backoff: 500ms
  rate-limit-threshold: 100
```

⚠️ **새 환경변수를 만들지 않는다.** `GITHUB_TOKEN` 은 `.env.example` 에 이미 있다
(`git show HEAD:.env.example` 로 확인 — 「GitHub」 절에 Q-1 근거 주석과 함께 존재).
`base-url` 은 `${...}` 를 거치지 않는 순수 프로퍼티다 — 바꿀 일이 테스트뿐이고,
env 를 하나 더 늘리면 `.env.example` 관리 표면만 넓어진다.

## 5. 구현 순서

### 실행 모드: sequential

유형이 `contract-and-impl` 이다. Stage 2·3 이 Stage 1 의 타입을 import 하므로
병렬 조건(「한 단계의 산출물을 다른 단계가 import 하지 않음」)을 충족하지 못한다.

| Stage | 내용 | 선행 |
|-------|------|------|
| 1 | `support` — 예외 · 스크럽 · 자격증명 · 에러 변환 · 재시도 · 클라이언트 | 없음 |
| 2 | `repository` — 값 3 + 능력 + 어댑터 | 1 |
| 3 | `issue` — 값 3 + 능력 + 어댑터 | 1 |
| 4 | 조립 — `GitHubClientConfig` · `application.yml` | 1~3 |
| 5 | 테스트 — 페이크 2 + 테스트 클래스 8 | 1~4 |

## 6. 테스트 계획 — 실행 결과 **74건 · 실패 0**

| 클래스 | 건수 | 무엇을 지키는가 |
|---|---|---|
| `GitHubErrorTranslatorTest` | 13 | **403 구분** — 1차(Remaining 0) / 2차(Retry-After) / 2차(본문 문구) / 429 / 권한. 401·404·5xx. HTTP-date `Retry-After`. 본문 토큰 스크럽. 깨진 헤더 |
| `GitHubApiClientTest` | 15 | **S-1** 공개 메서드 `get` 뿐 · 실제 동사 GET. **S-4** 헤더 인증·URL 무토큰·예외 사슬·**로그**. 레이트리밋 노출·임박 경고·「모름」. 304. 재시도/미재시도 4종 |
| `GitHubRepositorySourceTest` | 10 | 메타데이터 매핑 · 보관 저장소 · base64 · `ref`. **S-5** 404만 빈 값 / 403·1MB초과·디렉터리·symlink 는 예외 |
| `GitHubIssueSourceTest` | 8 | **PR 을 이슈로 취급하지 않음** · `Link` → hasNext · 304 ≠ 빈 결과 · 증분 쿼리 · 라벨 두 형식 |
| `GitHubRetryPolicyTest` | 8 | 일시적 실패만 재시도 · 레이트리밋 제외 · 상한 소진 · 선형 백오프 |
| `TokenRedactorTest` | 6 | **S-4** 토큰 5종 + Authorization 헤더 · 정상 메시지 무훼손 |
| `GitHubPropertiesTest` | 5 | **S-4** `toString` 마스킹 2종 · 기본값 · 불가능한 설정 거부 |
| `GitHubClientConfigTest` | 4 | 연결 타임아웃 명시 · **S-1** `RestClient` 빈 미노출 · 기본값 고정 |
| `GitHubCapabilityFakeTest` | 4 | 능력이 **실제로 페이크로 대체 가능한가**(규율 ③ 의 값어치) |
| `OssContributorAgentApplicationTests` | 1 | 토큰 없이도 컨텍스트가 뜬다(회귀 방지) |

**대외 호출 대체** — 능력 소비자 관점은 자체 페이크, HTTP 계층은 `MockRestServiceServer`.
🔴 실제 GitHub 를 타는 자동 테스트는 없다. 토큰 픽스처는 **런타임 조립**(`"ghp_" + "x".repeat(30)`)이라
소스에 토큰 패턴 리터럴이 존재하지 않는다 — 프로덕션·테스트 양쪽에 같은 방침을 적용했다.

### ⚠️ 검증 한계 (정직하게 남긴다)

| 항목 | 상태 |
|---|---|
| **읽기 타임아웃 실동작** | ❌ 미검증. `JdkClientHttpRequestFactory` 가 값을 되읽을 수단을 주지 않는다. 내부 필드 리플렉션은 「구현 내부 필드에 의존」 금지에 걸려 하지 않았다. 연결 타임아웃만 `HttpClient.connectTimeout()` 으로 확인 |
| **`MockRestServiceServer` 는 요청 팩토리를 교체한다** | 그래서 어댑터 테스트는 실제 타임아웃 설정을 타지 않는다. 설정 전달은 코드로 보장, 실동작은 수동 검증 몫 |

## 7. 리스크

| # | 리스크 | 대응 |
|---|--------|------|
| 1 | 계약이 #8 에서 흔들린다 | `IssueQuery` 에 `updatedSince`·`etag` 를, `fetchFile` 에 `ref` 를 **지금** 넣었다. **커서 관리·지연 정책은 넣지 않는다**(#8) |
| 2 | 「레이트리밋 노출」을 도메인까지 끌고 가고 싶어진다 | 노출을 `support/github` 로 한정. `IssuePage` 에 레이트리밋 필드 없음. FR-4 주 참조 |
| 3 | 2차 리밋 판정이 응답 형식 변화에 취약 | 세 신호의 OR. **어느 것도 확실하지 않으면 권한 오류**로 떨어뜨린다 — 오판의 결과가 무한 재시도가 아니라 빠른 실패가 된다 |
| 4 | 토큰 없이 기동해 401 만 받는다 | 기동 시 `WARN` 1회. **값이 아니라 존재 여부만** |
| 5 | 재시도가 실패를 증폭 | 상한 2 · 레이트리밋 제외. 이 PR 에는 호출자가 없어 실측 위험 0 |
| 6 | **#8 이 `Link` 파싱·304 를 다시 설계한다** | ⚠️ 이 PR 이 **#8 스코프를 일부 당겨왔다** — `Link` rel=next 판독과 304 처리는 「필드만 두기」를 넘어선 구현이다. 조회 1회를 올바로 수행하는 데 필요하다고 보고 포함했고, **커서 진전·지연·멱등 upsert 는 넘기지 않았다.** #8 은 이 어댑터를 쓰되 정책을 얹는다 |

**대외 호출 실패 시나리오**

| 시나리오 | 기대 동작 |
|---|---|
| 1차 리밋 (403 + `Remaining: 0`) | `GitHubRateLimitException(PRIMARY, resetAt)` — 재시도 안 함. 지연은 #8 |
| 2차 리밋 (403 + `Retry-After`) | `GitHubRateLimitException(SECONDARY, retryAfter)` — 권한 오류로 처리하지 않는다 |
| 진짜 권한 오류 (403, 신호 없음) | `GitHubPermissionException` — 즉시 실패 |
| 읽기 타임아웃 | `GitHubTransientException` → 상한(2) 내 재시도 → 소진 시 전파 |
| 404 (파일 없음) | `Optional.empty()`. 「`CONTRIBUTING.md` 가 없다」는 정상 상황 |
| **200 인데 내용 없음(1MB 초과)** | `GitHubUnreadableContentException` — **「없음」이 아니라 「보류」** (S-5) |

## 8. 복잡도

| Stage | 파일 | 복잡도 |
|-------|------|--------|
| 1 support | 13 | **높음** — 403 구분·재시도·스크럽·자격증명 |
| 2 repository | 5 | 중간 — `fetchFile` 실패 모드 |
| 3 issue | 5 | 중간 — PR 걸러내기·`Link` |
| 4 조립 | 2 | 낮음 |
| 5 테스트 | 10 | 중간 |

## 9. 문서 동기화

| 대상 | 필요 | 이유 |
|---|---|---|
| `codemaps/architecture.md` | ✅ | 능력 인터페이스 표의 **(제안)** 이 실물이 된다 · 구현 현황 행 추가 |
| **`rules/conventions/architecture.md`** | ✅ | §4 「재시도 상한은 `agent.execution.max-retries`」가 전송 계층 예산과 어긋난다 — 두 축을 구분하도록 개정 (가정 ①) |
| `rules/context/glossary.md` | ✅ | 능력 인터페이스 3개 · 1차/2차 레이트리밋 |
| `README.md` | ✅ | 구조 트리의 `support/` 설명 |
| `codemaps/data.md` | — | 엔티티·컬럼 변경 없음 |
| `codemaps/domain.md` | — | 상태·전이·필터 규칙 변경 없음 |
| `.env.example` | — | **새 환경변수 없음.** `GITHUB_TOKEN` 존재를 `git show HEAD:.env.example` 로 확인 |
| `rules/context/open-questions.md` | — | **닫히는 미결 없음.** Q-6·Q-9 는 가정으로 진행할 뿐 확정하지 않는다 |

## 10. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-22 | smileboy0014 | 초안 — 이슈 #6 · Q-1 확정 반영 |
| 2026-09-22 | smileboy0014 | 계획 검토 반영 — 자격증명 이음매(Q-1 「남은 것」) · S-5/S-6 판정 변경 · `fetchFile` 실패 모드 계약 · `ref` · 가정 ① 문구 · 검증 한계 명시 · 리스크 6 신설 |
