# 용어 사전

> 같은 단어가 두 가지를 가리키는 자리가 많다. 문서·코드·커밋에서 아래 표기를 따른다.

## 가장 헷갈리는 둘

| 용어 | 뜻 | 코드에서 |
|---|---|---|
| **이 저장소** | `ai-oss-contributor-agent` 자체 | — |
| **대상 저장소** (target repo) | 에이전트가 기여하는 외부 OSS | `OssRepository` 엔티티 |

`repository` 라는 말이 세 곳에서 다른 뜻이다. 반드시 구분해 쓴다.

| 표기 | 뜻 |
|---|---|
| `com.ossagent.repository` | **도메인 패키지** — 대상 저장소 관리 |
| `OssRepository` | **엔티티** — 등록된 대상 저장소 1건 |
| `OssRepositoryRepository` | **Spring Data JPA 인터페이스** — 영속 어댑터 |

DB 접근 인터페이스를 도메인 이름으로 줄여 쓰지 않는다(`RepositoryRepository` 같은 이름이 생긴다).

## 파이프라인 단계

| 용어 | 뜻 |
|---|---|
| **Policy Analysis** | 대상 저장소의 기여 규약을 수집·판정해 `RepositoryPolicy` 로 고정. **후보보다 먼저**이고 저장소 단위다 |
| **보류** (pending) | 규약을 **읽지 못해** 판정이 서지 않은 상태. `aiContributionAllowed = NULL`. 🔴 **「허용」이 아니다**(S-5) — 자동으로 풀리지 않고 사람이 해소한다(#24) |
| **Scan** | 대상 저장소의 open 이슈를 수집해 저장 |
| **Filter** | 규칙 기반 1차 배제 — 요구사항 불명확 · 대규모 아키텍처 변경 · ~~종료됨~~. **대외 호출을 하지 않는다** (#9).<br>⚠️ 「종료됨」은 구현돼 있으나 **발화하지 않는다** — 수집이 `state=open` 고정이라 닫힌 이슈가 데이터에 없다 (#14).<br>「활성 PR 존재」는 여기가 아니라 **#11 입구 + #23** 다 — S-2 방어의 이전 |
| **Analysis** | LLM 기반 기여 가능성 판정. 산출물은 category · difficulty · feasible · confidence 등 |
| **Candidate** | 분석을 통과해 기여 대상이 된 이슈. 상태머신의 주체 |
| **Repository Analysis** | 이슈 키워드로 대상 저장소의 관련 소스·테스트를 좁혀 찾는 단계. 저장소 전체를 LLM 에 넣지 않는다 |
| **Implementation Plan** | 수정할 파일과 방법. 검증(Validate)을 거쳐야 코딩으로 넘어간다 |
| **Verification** | 컴파일 → 유닛 → 통합 → 포맷 → diff 검사. **샌드박스 안에서** 수행 |
| **AI Review** | 생성된 diff 에 대한 LLM 리뷰. 실패 시 코딩 단계로 되돌린다 |
| **Draft PR** | 사용자 Fork 에서 원본으로 여는 **draft** 상태 PR. 여기서 자동화가 끝난다 |

## 도메인 객체

| 용어 | 뜻 |
|---|---|
| `RepositoryPolicy` | 대상 저장소의 **기여 규약** — java 버전 · 빌드/테스트 명령 · 이슈 참조 필수 · sign-off 필수 · 테스트 필수 |
| `AgentRun` | 파이프라인 한 단계의 **1회 실행 기록**. stage · attempt · 토큰 · 상태 · 에러 |
| `GeneratedChange` | AI 가 만든 변경분 — 브랜치 · 커밋 SHA · diff · 테스트 결과 · 리뷰 결과 |
| `PullRequest` | Fork URL · 브랜치 · PR 번호 · PR URL · 상태 |

## 능력 인터페이스 — domain 이 선언하고 adapter 가 구현한다

「능력 이름」과 「기술 이름」을 바꿔 쓰지 않는다. 기술 이름이 domain 에 나타나면 규율 ③ 위반이다.

| 능력 (domain) | 구현 (adapter/out) | 뜻 |
|---|---|---|
| `RepositorySource` | `GitHubRepositorySource` | 대상 저장소의 메타데이터·파일을 **읽는다**. 쓰기 없음 |
| `IssueSource` | `GitHubIssueSource` | 대상 저장소의 open 이슈를 **읽는다**. 코멘트 경로 없음(S-2) |
| `GitHubCredentials` | `StaticTokenCredentials` | 호출마다 자격증명을 공급한다. 단수명 토큰으로 갈아끼울 이음매 — Q-1 |
| `LanguageModel` | `AnthropicLanguageModel` | LLM 호출. **4개 지점이 공유하는 1층 능력** — 그 위에 `IssueAnalyst` 등 2층이 얹힌다 |
| `PolicyDocumentSource` | `GitHubPolicyDocumentSource` | 규약 후보 문서 수집. **실패를 예외가 아니라 값으로** 돌려준다 — 무엇을 못 읽었는지가 곧 보류 사유다 |
| `ContributionRuleInterpreter` | `LlmContributionRuleInterpreter` | 규약 판정. `LanguageModel` 위에 얹히는 2층 |
| `RecordingLanguageModel` | — | 기록 강제 **데코레이터**. 노출되는 `LanguageModel` 빈은 이것뿐이라 기록을 건너뛸 경로가 없다 |
| `PromptScrubber` | `TokenRedactingPromptScrubber` | 송신 **직전** 프롬프트 시크릿 제거. S-4 에서 「밖으로 나가는 것」을 막는 유일한 방어 |
| `AgentRunRecorder` | `RecordAgentRunUseCase` (candidate) | 실행 이력 기록. `AgentRun` 이 남의 애그리거트라 능력으로 뒤집었다 |
| `RepositoryCoordinates` | — | `owner/name` 값 타입. `repository` 가 소유하고 다른 도메인이 import 한다 |
| `IssueSnapshot` | — | 수집 시점의 이슈 원본 **값**. 영속 엔티티 `Issue` 와 다르다 |

## 증분 수집 — 「언제 돌렸나」와 「어디까지 봤나」는 다르다 (#8)

| 용어 | 뜻 | 코드에서 |
|---|---|---|
| **스캔 시각** | 우리가 **언제 돌렸나** | `oss_repository.last_scanned_at` |
| **증분 커서** | 데이터를 **어디까지 봤나** — 다음 조회의 `since` | `oss_repository.issue_cursor_updated_at` |
| **조건부 요청** | `If-None-Match` 로 「안 바뀌었으면 304 만 달라」 | `issue_cursor_etag` → `IssueQuery.etag` |
| **워터마크** | 이번에 본 것 중 가장 늦은 `updated_at` | 커서의 다음 값 |

⚠️ 둘을 **겹쳐 쓰지 않는다.** 스캔 시각을 커서로 재사용하면 **스캔이 실패해도 커서가
전진해 이슈를 영구히 건너뛴다.**

⚠️ **ETag 는 `since`·`page` 와 짝이다.** URL 이 바뀌면 이전 ETag 는 다른 리소스의 것이다.
커서가 전진하면 버린다 — 우연히 매치되어 304 를 받으면 **그 페이지를 통째로 건너뛴다.**

⚠️ 「지연」과 「실패」를 바꿔 쓰지 않는다. **레이트리밋은 지연**이고, 지연은
`ScanResult.delayedUntil` 로 나온다. 예외로 나가는 것은 **권한 오류 같은 진짜 실패**뿐이다.

## 레이트리밋 — 1차와 2차를 바꿔 쓰지 않는다

둘 다 **403 으로 온다.** 구분하지 못하면 한쪽은 영구 실패가 되고 다른 쪽은 무한 재시도가 된다.

| 용어 | 신호 | 뜻 | 대응 |
|---|---|---|---|
| **1차 레이트리밋** | `X-RateLimit-Remaining: 0` | 시간당 할당량 소진 (인증 5,000/h · Search 30/min) | `resetAt` 까지 **지연** |
| **2차 레이트리밋** | `Retry-After` · 본문의 `secondary`·`abuse` · 429 | abuse detection. 단시간 집중 호출에 걸린다 | `Retry-After` 만큼 **지연** |
| **권한 오류** | 위 신호가 **하나도 없는** 403 | 토큰 스코프 부족 · 접근 불가 저장소 | 재시도하지 않고 실패 |

⚠️ 「리밋에 걸렸다」를 **실패**라고 쓰지 않는다. 정상 운영 상황이고 대응은 **지연**이다.
재시도로 다루면 남은 예산을 더 태운다.

## 상태

### 필터 판정 — 후보 상태와 다른 축이다 (#9)

`Issue.filterResult` 의 어휘이고, 아래 `CandidateStatus` 와 **섞어 쓰지 않는다.**
「배제」가 두 곳에 있어 헷갈리는 자리다 — 이쪽은 규칙이 이슈를 거른 것이고,
`REJECTED`(후보)는 LLM 분석이 후보 자격을 부정한 것이다.

| 용어 | 뜻 | 후보가 되나 |
|---|---|---|
| `NULL` | **아직 판정하지 않았다** | — |
| `PASSED` | 어떤 규칙에도 걸리지 않았다 | ✅ |
| **`UNDECIDED`** | **규칙으로 가를 수 없다 — LLM 이 본다**(#11). 「판정하지 않았다」를 「통과」로 적지 않기 위해 존재한다 | ✅ |
| `REJECTED` | 규칙이 배제했다 | ❌ |

⚠️ 「필터를 **통과한** 이슈」라고 쓰지 않는다. `UNDECIDED` 도 후보가 되므로
**「배제되지 않은 이슈」**가 맞다. 표현이 흐려지면 하류가 보류를 통과로 뭉갠다.

| 용어 | 뜻 |
|---|---|
| `DISCOVERED` → `ANALYZED` | 수집됨 → 분석 완료 |
| `SELECTED` | **사람이** 기여하기로 고른 상태. 자동으로 여기 도달하지 않는다 |
| `IMPLEMENTING` / `TESTING` / `REVIEWING` | 구현 / 검증 / AI 리뷰 진행 중 |
| `READY_FOR_PR` → `PR_CREATED` | PR 생성 대기 → Draft PR 생성 완료 (**종단**) |
| `REJECTED` | 후보 자격 미달 (**종단**) |
| `FAILED` | 재시도 상한 소진 (**종단**) |

## 외부 주체

| 용어 | 뜻 |
|---|---|
| **Upstream** | 원본 저장소. **읽기 전용** |
| **Fork** | 사용자 계정의 포크. **유일한 쓰기 대상** |
| **Maintainer** | 대상 저장소의 관리자. 우리가 만든 Draft PR 을 사람이 제출한 뒤에야 마주한다 |
| **Sandbox** | 대상 저장소 빌드·테스트를 격리 실행하는 Docker 컨테이너 |

## 토큰 — 이름이 비슷해서 바꿔 끼우기 쉽다

| 용어 | 뜻 | 이 프로젝트에서 |
|---|---|---|
| **classic PAT** | 스코프 단위 개인 토큰 (`public_repo` 등) | ✅ **우리가 쓰는 것** (Q-1) |
| **fine-grained PAT** | 저장소별·권한별 세분화 토큰 | ❌ **쓸 수 없다** — 멤버가 아닌 upstream 에 PR 생성 불가(403) |
| **설치 토큰** (installation token) | GitHub App 이 설치된 저장소에서 쓰는 토큰 | ❌ upstream 에 설치될 리 없다 |
| **사용자 대행 토큰** (user-to-server) | GitHub App 이 OAuth 로 사용자를 대행 | 다중 사용자 확장 시의 경로 |

⚠️ 「fine-grained 가 더 안전하니 바꾸자」는 판단이 반복해서 나올 자리다.
바꾸면 **PR 생성이 403 으로 죽는다.** 근거는 [`open-questions.md`](./open-questions.md) Q-1.

## 테스트 대역 — 층마다 다른 것을 본다 (Q-9)

| 용어 | 뜻 | 코드에서 |
|---|---|---|
| **능력 대역** (페이크) | 능력 인터페이스의 테스트 구현. 호출을 기록하고 **실패 모드를 재현**한다 | `Fake{능력이름}` + `@FakeAdapter` — 능력과 같은 패키지의 `src/test` |
| **`@ExternalAdapter`** | 「대외 시스템을 실제로 타는 빈」 표시. 테스트에서 **빠진다** | `com.ossagent.support` (src/main) |
| **`@FakeAdapter`** | 대역 표시. 테스트에서만 **뜨고**, 컴포넌트 스캔으로 **자동 등록**된다 | `com.ossagent.support.testing` (src/test) |
| **값 픽스처** | 값 객체를 만드는 static factory | `{타입}Fixtures` |
| **양성 대조** | 가드가 **0건을 검사하고 초록**이 되는 것을 막는 단언 | 「실제 빈을 찾았고 허용으로 판정했다」 |
| **통합 테스트 진입점** | `@SpringBootTest` 대신 쓰는 합성 애노테이션 | `@AgentIntegrationTest` |

⚠ 이름에 **`Mock`·`Stub` 을 쓰지 않는다** — Mockito 의 mock 과 섞여 「무엇이 검증 대상인지」가 흐려진다.

⚠ 「페이크로 대체한다」가 **모든 층에 같은 뜻이 아니다.** 능력 층은 자체 페이크, 어댑터 매핑 층은
`MockRestServiceServer`, 전송 계약 층은 WireMock 이다. 한 단어로 뭉쳐 부르면 층이 하나 빠진다.

## 혼동 주의

| 쓰지 말 것 | 쓸 것 | 왜 |
|---|---|---|
| 「PR 을 올린다」 | 「Draft PR 을 만든다」 | 제출은 사람이 한다. 표현이 흐려지면 코드도 흐려진다 |
| 「저장소에 push」 | 「Fork 에 push」 | S-1 위반이 문장에서 시작된다 |
| 「테스트를 돌린다」 | 「샌드박스에서 테스트를 돌린다」 | S-3 |
| 「필터를 통과한 이슈」 | 「배제되지 않은 이슈」 | `UNDECIDED` 도 후보가 된다. 「통과」로 뭉개면 판정이 흐려진다 (#9) |
| 「에이전트」 단독 | 「코딩 에이전트」 / 「이 제품」 | 제품 전체와 내부 LLM 실행자가 같은 말이 된다 |
