# ai-oss-contributor-agent

Java/Spring OSS 의 GitHub Issue 를 탐색해 **Draft PR 까지** 준비하는 Spring Boot 애플리케이션.
실제 제출·수정·머지는 사람이 한다.

- 프로젝트 개요: [`README.md`](./README.md)
- 제품 요구사항: [`docs/ai-oss-contributor-agent-prd.md`](./docs/ai-oss-contributor-agent-prd.md)
- 프로젝트 전반: [`.claude/rules/context/project-overview.md`](./.claude/rules/context/project-overview.md)
- **착수 전 확인 ①**: [`.claude/rules/context/safety-boundaries.md`](./.claude/rules/context/safety-boundaries.md) — 넘으면 안 되는 선 6개
- **착수 전 확인 ②**: [`.claude/rules/context/open-questions.md`](./.claude/rules/context/open-questions.md) — 미결 대장
- Claude 세팅 인덱스: [`.claude/README.md`](./.claude/README.md)

## 이 저장소에서 「저장소」는 두 가지를 뜻한다

혼동하면 규칙이 거꾸로 적용된다. 문서에서 항상 구분해 쓴다.

| 말 | 뜻 |
|---|---|
| **이 저장소** · 우리 코드 | `ai-oss-contributor-agent` 자체. `.claude/` 의 워크플로우 규칙이 적용되는 대상 |
| **대상 저장소** (target repo) | 에이전트가 **런타임에** 기여하는 외부 OSS (`spring-projects/spring-kafka` 등). 여기에 대한 금지사항은 우리 워크플로우 규칙이 아니라 **우리가 짜는 코드가 지켜야 할 불변식**이다 |

예: 「원본 저장소에 직접 push 금지」는 *내가 이 저장소에 push 하지 말라*는 뜻이 아니라,
*우리 코드가 대상 저장소에 push 하는 경로를 만들지 말라*는 뜻이다.
