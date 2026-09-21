# 규칙 문서 — 읽는 순서

`.claude/rules/` 와 `.claude/codemaps/` 에 무엇이 있고 언제 읽는지.

## 읽는 순서

```
① safety-boundaries.md   ← 넘으면 안 되는 선. 무엇을 하든 먼저
② project-overview.md    ← 무엇을 만드나 · 지금 어디까지 와 있나
③ open-questions.md      ← 착수 전 미결 대조
④ codemaps/*             ← 구조 파악
⑤ conventions/*          ← 코드를 쓰기 직전
```

## rules/context/ — 도메인 컨텍스트

| 문서 | 언제 읽나 |
|---|---|
| [`safety-boundaries.md`](../rules/context/safety-boundaries.md) | **항상.** S-1~S-6 은 리뷰 무조건 블로킹이다 |
| [`project-overview.md`](../rules/context/project-overview.md) | 세션 시작 · 범위 판단이 필요할 때 |
| [`glossary.md`](../rules/context/glossary.md) | 「저장소」·「에이전트」처럼 두 뜻을 가진 말을 쓸 때 |
| [`external-deps.md`](../rules/context/external-deps.md) | GitHub·LLM·샌드박스·DB 를 건드릴 때 |
| [`open-questions.md`](../rules/context/open-questions.md) | **구현 착수 전 필수 대조.** 추측으로 채우지 않는다 |

## rules/conventions/ — 코드 컨벤션

| 문서 | 언제 읽나 |
|---|---|
| [`architecture.md`](../rules/conventions/architecture.md) | **코드를 쓰기 전.** 레이어 배치·규율 4줄·완화 2개 |
| [`commit-convention.md`](../rules/conventions/commit-convention.md) | 커밋할 때 |
| [`git-workflow.md`](../rules/conventions/git-workflow.md) | 브랜치를 자를 때 |
| [`pr-convention.md`](../rules/conventions/pr-convention.md) | PR 을 올릴 때 |
| [`testing-philosophy.md`](../rules/conventions/testing-philosophy.md) | 테스트를 쓸 때 · 대외 의존을 무엇으로 대체할지 |
| [`logging.md`](../rules/conventions/logging.md) | 로그를 남길 때 (시크릿 유출이 가장 잦은 곳) |

## codemaps/ — 구조 지도

| 문서 | 담는 것 |
|---|---|
| [`architecture.md`](../codemaps/architecture.md) | 도메인 구성 · 파이프라인 단계별 소유 · 외부 경계 · 구현 현황 |
| [`data.md`](../codemaps/data.md) | 테이블·컬럼·인덱스·멱등키 |
| [`domain.md`](../codemaps/domain.md) | 상태머신 · 필터 규칙 · 재시도 · 불변식 |

코드맵은 **구조가 바뀌면 같은 브랜치에서 갱신**한다 — [`/update-codemaps`](../skills/update-codemaps/SKILL.md).

## 충돌하면 무엇이 이기나

```
safety-boundaries.md  >  conventions/*  >  codemaps/*  >  기존 코드
```

- 안전 경계는 다른 모든 규칙을 이긴다. 편의·성능·일정으로 양보하지 않는다
- 컨벤션과 기존 코드가 다르면 **컨벤션이 맞다.** 기존 코드는 규칙 이전에 쓰인 것일 수 있다
- 코드맵과 실제 코드가 다르면 **코드가 맞다.** 코드맵을 고친다

## 우리 규칙 vs 대상 저장소 규칙

`.claude/rules/` 전체는 **이 저장소 안에서만** 유효하다.
대상 저장소로 나가는 커밋·PR·브랜치는 그쪽 `CONTRIBUTING.md` 를 따른다 — S-5.
우리 컨벤션을 남의 저장소에 강요하는 코드는 반려다.
