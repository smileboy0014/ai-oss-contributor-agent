# 스킬 11개

`.claude/skills/{name}/SKILL.md`. 슬래시 진입점은 [`commands.md`](commands.md).

## 워크플로우 — 티켓 하나를 끝내는 경로

| 스킬 | 언제 | 산출물 |
|---|---|---|
| [`work`](../skills/work/SKILL.md) | **단일 진입점.** 계획 → 구현·테스트 → 리뷰 → 코드맵 → PR 을 오케스트레이션 | PR |
| [`plan`](../skills/plan/SKILL.md) | 요구사항 → 코드 레벨 구현 계획 | `docs/plans/PLAN-{issue}.md` |
| [`commit`](../skills/commit/SKILL.md) | 컨벤션에 맞는 커밋 메시지 | 커밋 |
| [`pr`](../skills/pr/SKILL.md) | PR 생성 (base `main`) | PR |
| [`pr-review`](../skills/pr-review/SKILL.md) | 코드 리뷰 / PR 코멘트 대응 | 리뷰 결과 |

```
/work #12 feature
   └─ /plan ─▶ 구현 ─▶ /commit ─▶ /pr ─▶ /pr-review ─▶ (사람) 머지
```

개별 호출도 된다. `/work` 는 그 조립일 뿐이다.

## 정의 — 만들기 전에 정한다

| 스킬 | 언제 |
|---|---|
| [`discuss`](../skills/discuss/SKILL.md) | **무엇을 할지가 아직 흐릿할 때.** TOC 13요소로 구조화하고 FRT 게이트를 통과하면 액션으로 전환 |
| [`prd`](../skills/prd/SKILL.md) | 정책·요구사항을 문서로 고정 |

`/discuss` → `/prd` → `/plan` → `/work` 가 가장 긴 경로다. 대부분은 `/work` 부터 시작한다.

## 유지보수 — 하네스 자신을 고친다

| 스킬 | 언제 |
|---|---|
| [`update-codemaps`](../skills/update-codemaps/SKILL.md) | 구조가 바뀌었다 — 코드맵 3개 갱신 |
| [`antipattern`](../skills/antipattern/SKILL.md) | 「이거 하지 마」 — 재발을 훅 또는 체크리스트로 구조화 |
| [`improve-skill`](../skills/improve-skill/SKILL.md) | 스킬·훅이 무언가를 놓쳤다 |
| [`handoff`](../skills/handoff/SKILL.md) | 세션 중간 이탈 — 다음 세션으로 바톤 터치 |

**세션에서 같은 실수가 두 번 나오면 메모가 아니라 하네스를 고친다.**
`/antipattern` 과 `/improve-skill` 이 그 자리다.

## 기준 프로젝트에서 뺀 것

| 스킬 | 왜 |
|---|---|
| `jira` | Jira 를 쓰지 않는다. 티켓은 GitHub 이슈다 |
| `confluence` | Confluence 를 쓰지 않는다. 문서는 저장소 안에 둔다 |

## 스킬을 읽는 순서

전부 읽을 필요는 없다. 상황에 맞는 하나만 읽는다.
**다만 어떤 스킬을 쓰든 [`safety-boundaries.md`](../rules/context/safety-boundaries.md) 는 먼저 읽는다** —
모든 스킬이 그것을 상위 제약으로 참조한다.

## 발동

- 사용자가 `/work` 처럼 직접 입력
- [`skill-rules.json`](../skill-rules.json) 의 키워드가 대화에 등장

키워드 매칭은 **보장이 아니라 힌트**다. 확실한 강제는 훅뿐이다 — [`hooks.md`](hooks.md).

## 추가하기

[`contributing.md`](contributing.md) 「스킬 추가」 참조. 핵심 셋:

- `SKILL.md` 300줄 초과 → `references/` 로 분리
- `description` 은 **언제 발동해야 하는지**를 쓴다
- `commands/{name}.md` 는 **얇은 포인터** — 본문을 복제하면 둘이 어긋난다
