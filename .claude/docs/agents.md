# 서브에이전트 6개

`.claude/agents/` 에 정의된 에이전트. 격리된 컨텍스트에서 돌며, 호출자는 결과만 받는다.

## 목록

| 에이전트 | 역할 | 도구 | 쓰기 |
|---|---|---|---|
| [`worker`](../agents/worker.md) | 단일 구현 태스크 전담 | Read · Edit · Write · Bash · Glob · Grep | ✅ |
| [`verifier`](../agents/verifier.md) | 구현이 요구사항을 충족하는지 독립 검증 | Read · Glob · Grep · Bash | ❌ |
| [`debugger`](../agents/debugger.md) | 근본 원인 분석 — 재현→증거→역추적→가설→수정 | Read · Edit · Bash · Glob · Grep | ✅ |
| [`gap-analyzer`](../agents/gap-analyzer.md) | 누락 요구사항·엣지케이스·**안전 경계 우회 경로** 탐지 | Read · Glob · Grep | ❌ |
| [`safety-reviewer`](../agents/safety-reviewer.md) | **안전 경계 S-1~S-6 를 호출 그래프까지 따라가 검증** | Read · Glob · Grep · Bash | ❌ |

`_shared/preflight.md` 는 에이전트가 아니라 **공통 프로토콜**이다. 모든 에이전트가 시작 전 CHARTER_CHECK 5줄을 출력한다.

## 검증 에이전트는 읽기 전용이다

`verifier` · `gap-analyzer` · `safety-reviewer` 에 Edit·Write 를 주지 않았다.
**구현자가 자기 일을 검증하면 통과시키는 쪽으로 기운다.** 도구 수준에서 막는 것이 확실하다.

같은 이유로 `/work` 의 계획 검토 단계는 `fork` 가 아니라 **별도 타입의 서브에이전트**를 쓴다 — 셀프 승인 방지.

## safety-reviewer 를 왜 따로 두나

[`safety-boundary-check.sh`](../scripts/safety-boundary-check.sh) 훅은 **스테이징된 파일의 문자열만** 본다.
grep 으로 잡히지 않는 것이 이 프로젝트에서 가장 위험하다:

- 능력 인터페이스 뒤에 숨은 구현체의 위반 — **인터페이스만 보면 드러나지 않는다**
- 설정·조건 분기를 타고 뒤집히는 draft 플래그
- **있어야 할 방어가 없는 것** (Fork owner 어설션 부재 · 프롬프트 스크럽 부재) — 없는 것은 grep 으로 못 찾는다

`safety-reviewer` 는 `implements` 를 grep 해 **구현체를 전부 열어보고**, 위험 인자를 리터럴까지 역추적한다.
확인하지 못한 항목을 ✅ 로 적지 않는 것이 이 에이전트의 규칙이다.

## 언제 호출하나

| 상황 | 에이전트 |
|---|---|
| 계획을 세운 뒤 티켓 대비 검토 | 격리 서브에이전트 (fork 금지) |
| 여러 파일에 걸친 독립 구현을 병렬로 | `worker` × N |
| 구현이 끝났는데 요구를 다 채웠는지 모르겠다 | `verifier` |
| 버그 원인을 모르겠다 | `debugger` |
| 뭘 빠뜨렸는지 모르겠다 | `gap-analyzer` |
| **안전 경계에 닿는 변경** | `safety-reviewer` — [`/pr-review`](../skills/pr-review/SKILL.md) high 깊이에서 동반 |

⚠️ `safety-reviewer` 는 **`.claude/agents/` 정의 파일 경로를 주고 그대로 따르게** 호출한다.
서브에이전트 타입으로 등록돼 있지 않다.

⚠️ 백그라운드로 돌므로 **결과가 오기 전에 턴을 끝내지 않는다.**

## 에이전트가 없을 때

참조한 에이전트가 없으면 **임의로 대체하지 않는다.** 이름이 바뀐 것인지 확인하고,
없으면 그 사실을 보고에 남긴다. 조용히 다른 수단으로 갈음하면 규정 단계가 생략된 채 통과한다.

## 추가하기

[`docs/contributing.md`](contributing.md) 「에이전트 추가」 참조.
