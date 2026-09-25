# `.claude/` 에 무언가를 추가할 때

## 어디에 두나

```
새로 알게 된 것이 생겼다.
├─ 기계적으로 탐지 가능한 위반인가?
│   └─ YES → 훅  (.claude/scripts/ + settings.json)          강제력 100%
├─ 판단이 필요한 워크플로우인가?
│   └─ YES → 스킬 (.claude/skills/{name}/SKILL.md)            강제력 ~80%
├─ 도메인·구조에 대한 사실인가?
│   ├─ 규칙·제약 → rules/context/ 또는 rules/conventions/
│   └─ 구조 지도 → codemaps/
└─ 그 외 배경지식 → memory (최후 수단)                          강제력 ~50%
```

**위계를 지키는 이유** — memory 에 적어 둔 규칙은 다음 세션이 떠올리지 못할 수 있다.
훅에 넣으면 다음에 시도하는 순간 막힌다. 확실성의 차이가 크다.

## 규칙 추가 — `rules/`

| 두는 곳 | 성격 |
|---|---|
| `rules/context/` | **이 프로젝트가 무엇인가** — 도메인·용어·외부 의존·제약·미결 |
| `rules/conventions/` | **코드를 어떻게 쓰나** — 아키텍처·커밋·git·PR·테스트·로깅 |

작성 규칙:
- 각 규칙에 **왜**를 붙인다. 「어기면 무슨 일이 생기는지」가 없으면 다음 사람이 무시한다
- 되돌릴 수 없는 것은 🔴 로 표시한다
- 추가했으면 [`docs/rules.md`](rules.md) 표에 한 줄 건다. **안 걸면 다음 세션이 찾지 못한다**

## 스킬 추가 — `skills/`

```
.claude/skills/{name}/
├── SKILL.md              frontmatter(description 한 줄) + 본문
└── references/           길어서 본문에 못 넣는 절차·템플릿
```

- `SKILL.md` 는 **300줄을 넘기지 않는다.** 넘으면 `references/` 로 뺀다
- `description` 은 **언제 발동해야 하는지**를 쓴다. 무엇을 하는지가 아니다
- 슬래시 진입점이 필요하면 `commands/{name}.md` 를 **얇은 포인터로** 같이 만든다 (본문 복제 금지)
- [`skill-rules.json`](../skill-rules.json) 에 키워드를 등록한다
- 추가했으면 [`docs/skills.md`](skills.md)·[`docs/commands.md`](commands.md) 에 한 줄 건다

## 에이전트 추가 — `agents/`

```markdown
---
name: my-agent
description: 무엇을 하는 에이전트인지
tools: Read, Glob, Grep
model: inherit
maxTurns: 15
---
```

- 본문 첫 절에 「사전 점검 — `_shared/preflight.md` 의 CHARTER_CHECK 출력」을 넣는다
- **검증 에이전트는 읽기 전용**으로 만든다 (`tools` 에 Edit·Write 를 넣지 않는다).
  구현자가 자기 일을 검증하면 통과시키는 쪽으로 기운다
- 결과 형식을 표로 고정한다. 자유 서술이면 호출자가 파싱하지 못한다

## 훅 추가 — `scripts/` + `settings.json`

[`docs/hooks.md`](hooks.md) 「훅 추가하기」 참조. 핵심 둘:

- **오탐 최소화** — 넓은 규칙은 결국 `--no-verify` 로 우회된다
- **에러 메시지가 곧 문서** — 「왜 안 되는지」와 「대신 뭘 쓰는지」를 같이 출력한다

## 코드맵 갱신 — `codemaps/`

구조가 바뀌면 **같은 브랜치에서** 갱신한다. [`/update-codemaps`](../skills/update-codemaps/SKILL.md).

갱신이 필요한 신호:
- 도메인 패키지 추가·삭제 · 능력 인터페이스 변경 → `architecture.md`
- 테이블·컬럼·인덱스·멱등키 변경 → `data.md`
- 상태·전이·필터 규칙·불변식 변경 → `domain.md`

각 파일 하단 `## 변경 이력` 표에 최상단 추가. 작성자는 GitHub username.

## 문서 하나를 바꿀 때 같이 봐야 하는 것

| 바꾼 것 | 같이 확인 |
|---|---|
| `safety-boundaries.md` 조항 | `safety-boundary-check.sh` · `agents/safety-reviewer.md` · `skills/pr-review` · `skills/antipattern` |
| 커밋/PR 컨벤션 | `skills/commit` · `skills/pr` — 스킬은 규칙의 실행 절차일 뿐이다 |
| 빌드 명령 | `../../.github/workflows/build.yml` · `scripts/impl-test-loop.sh` · `agents/worker.md` · `agents/verifier.md` |
| 커밋 훅 구성 | `../../.githooks/pre-commit` · `docs/hooks.md` · `rules/conventions/commit-convention.md` · `docs/setup.md` |
| 패키지 구조 | `docs/structure.md` · `codemaps/architecture.md` · 커밋 scope 표 · 루트 `README.md` |
| 새 환경변수 | `.env.example` · `docs/setup.md` |

**두 곳이 어긋나면 하네스가 조용히 틀린 것을 강제하게 된다.** 이게 가장 찾기 어려운 버그다.

## 자기 개선 루프

| 상황 | 스킬 |
|---|---|
| 「이거 하지 마」 — 재발을 구조로 막고 싶다 | [`/antipattern`](../skills/antipattern/SKILL.md) |
| 스킬·훅이 무언가를 놓쳤다 | [`/improve-skill`](../skills/improve-skill/SKILL.md) |

세션에서 같은 실수가 두 번 나오면 **메모가 아니라 하네스를 고친다.**
