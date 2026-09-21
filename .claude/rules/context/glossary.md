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
| **Scan** | 대상 저장소의 open 이슈를 수집해 저장 |
| **Filter** | 규칙 기반 1차 배제 — 종료됨 · 활성 PR 존재 · 요구사항 불명확 · 대규모 아키텍처 변경 |
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

## 상태

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

## 혼동 주의

| 쓰지 말 것 | 쓸 것 | 왜 |
|---|---|---|
| 「PR 을 올린다」 | 「Draft PR 을 만든다」 | 제출은 사람이 한다. 표현이 흐려지면 코드도 흐려진다 |
| 「저장소에 push」 | 「Fork 에 push」 | S-1 위반이 문장에서 시작된다 |
| 「테스트를 돌린다」 | 「샌드박스에서 테스트를 돌린다」 | S-3 |
| 「에이전트」 단독 | 「코딩 에이전트」 / 「이 제품」 | 제품 전체와 내부 LLM 실행자가 같은 말이 된다 |
