---
description: GitHub 이슈 작업의 단일 진입점 — type(feature/fix/refactor/chore/quick) 프로필에 맞춰 계획 → 구현·테스트 → PR → 리뷰 → 이슈 정리 lifecycle 을 오케스트레이션합니다. "/work 12 feature" 형태로 호출.
---

# /work — 이슈 작업의 단일 진입점

> 이 저장소(`ai-oss-contributor-agent`) 자체를 개발하는 워크플로우다.
> 에이전트가 **대상 저장소**에 하는 일과 헷갈리지 않는다 — [`CLAUDE.md`](../../../CLAUDE.md) 의 용어 구분을 먼저 본다.

## 사용법

```
/work <issue|slug> [feature|fix|refactor|chore|quick]
```

- `<type>` 미지정 → 이슈 본문·키워드로 1차 추정 후 **AskUserQuestion 컨펌 필수** — 추정만으로 진행 절대 금지.
  "묻지 말고 진행" 지시가 있어도 이 컨펌만은 예외다(브랜치·PR·리뷰 깊이 전체를 좌우)

```bash
/work 12 feature              # GitHub 이슈 #12 — 신규 기능
/work 34 fix                  # 버그 수정
/work gradle-migration chore  # 이슈 없음 — 슬러그로 진행
/work 12                      # type 미지정 → 추정 + 컨펌 질문
```

## 브랜치 체제

| 항목 | 값 |
|---|---|
| 작업 베이스 | **`main`** — 전 타입이 여기서 절단되고 여기로 머지된다 |
| 머지 | **Squash Merge** 고정 |
| 승격 사다리 | **없다** — `develop`·`release/*` 를 두지 않았다 |

1인 개발이고 배포 대상이 없어 승격 사다리를 만들면 관리 비용만 생긴다.
배포가 생기면 `develop` 을 도입하고 [`git-workflow.md`](../../rules/conventions/git-workflow.md) 와 이 절을 함께 갱신한다.

**`main` 직접 커밋은 하지 않는다.** 히스토리에 직접 커밋 2건이 있지만 여기서부터는 PR 로만 간다.

## 작업 격리 — worktree 필수 🔴

**모든 `/work` 실행은 전용 git worktree 안에서 한다.** 메인 체크아웃에서 브랜치를 절단해 작업하지 않는다.
「충돌이 우려되면」이 아니라 **예외 없이 항상**이다.

체크아웃이 하나뿐이면 동시에 들어온 두 작업이 서로를 덮어쓴다 — 한쪽의 `git checkout` 이 다른 쪽의
미커밋 변경을 끌고 다니고, `./gradlew build` 는 같은 `build/` 를 두 세션이 나눠 쓴다.
빌드 결과가 누구 것인지 알 수 없으면 **로컬 build 가 유일한 게이트**(Q-10)라는 전제가 무너진다.

| 항목 | 값 |
|---|---|
| 위치 | `.claude/worktrees/{prefix}/{issue}_{slug}` — 작업 1건 = 디렉토리 1개 |
| 베이스 | `origin/main` (fetch 후 절단) |
| 수명 | 이슈 1건 = worktree 1개. **머지 후 Phase 6 에서 회수** |
| 메인 체크아웃 | `main` 에 둔 채 건드리지 않는다 — 작업물을 남기지 않는다 |

⚠️ `.claude/worktrees/` 는 `.gitignore` 대상이다. worktree 디렉토리가 스테이징되면 반려.

## 타입 프로필

| type | Phase 1 (계획) | 브랜치 | 특이사항 |
|------|---------------|--------|---------|
| `feature` | `/plan` + 격리 서브에이전트 검토 | `feature/{issue}_{slug}` | 계약 표면이면 PR 본문 명시 |
| `refactor` | `/plan` (간략) + 검토 | `refactor/{issue}_{slug}` | 동작 보존 |
| `fix` | `/plan` (약식) — 검토 없음 | `fix/{issue}_{slug}` | 원인·수정·검증 3절 |
| `chore` | 생략 (PR 본문 1줄) | `chore/{issue}_{slug}` | 리뷰 skip |
| `quick` | 생략 | `feature/{issue}_{slug}` | feature 와 계획 생략만 다름 |

이슈 번호가 없으면 `{prefix}/_{slug}` 로 절단한다.

## 무인 진행 원칙

lifecycle 은 사람 응답을 기다리지 않고 끝까지 진행한다. 판단 지점은 자동 판정하고, 막히면 **선택 게이트**(AskUserQuestion — 진행/수정/중단)를 띄운다. 조용히 죽지 않는다. 질문 불가 환경(서브에이전트 등)이면 같은 질문·미해결 목록을 **최종 보고에 담아 종료**한다.

사람 몫 3가지 — type 컨펌(Phase 0) · **안전 경계·미결 게이트**(Phase 1) · PR 머지(범위 밖).

## 단계

```
[Phase 0] 타입 결정 + 재진입 감지
  0. ⚠ gh 활성 계정 확인 (선행 조건 — 이 저장소는 smileboy0014 로 작업한다):
       gh auth status
     활성 계정이 다르면 gh auth switch --user smileboy0014 로 전환하고 시작한다.
     다른 계정이면 push·PR 이 403 으로 죽는다 — [setup.md](../../docs/setup.md) 「gh 활성 계정」.
     전환은 전역 상태이므로 결과 보고에 남긴다

  1. 인자 파싱 (이슈 미지정 시 브랜치명에서 추출)
  1.5 재진입 감지 — 처음부터 다시 돌지 않는다 (git worktree list 를 먼저 본다):
      ├─ 이슈 worktree 존재 → 그 worktree 로 들어가 이어서 (새로 만들지 않는다)
      ├─ PR 머지됨 → Phase 6 직행
      ├─ PR open → 리뷰 코멘트 있으면 [리뷰 코멘트 대응] / 빌드 적색이면 Phase 2 회귀
      ├─ 브랜치·커밋 존재 → 기존 산출물 입력으로 Phase 2 이어서
      └─ docs/plans/PLAN-{issue}.md 만 존재 → 요구 변경 없으면 채택 후 Phase 2
  2. type 미지정 → gh issue view 후 추정 → ⚠ AskUserQuestion 강제
     (추정 type 첫 옵션 + Recommended + 근거)
  3. TodoWrite 로 해당 type 의 phase 들을 진행 추적 등록

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[Phase 0.5] 작업 격리 — worktree 분기   (전 타입 필수 · 예외 없음)
  ⚠ 여기를 건너뛰고 메인 체크아웃에서 브랜치를 절단하면 동시 작업이 서로를 덮어쓴다.

  1. 기존 worktree 확인 — 재진입이면 새로 만들지 않는다:
       git worktree list
     ├─ {prefix}/{issue}_{slug} 가 이미 있다 → 그 경로로 들어가 이어서 작업
     └─ 없다 → 2 로

  2. 분기 (둘 중 하나 · 결과는 같다 — origin/main 기준 새 브랜치 + 전용 디렉토리):
     · EnterWorktree 툴이 있으면 → EnterWorktree(name: "{prefix}/{issue}_{slug}")
       `.claude/worktrees/{prefix}/{issue}_{slug}` 생성 + 세션 작업 디렉토리 전환
       재진입은 EnterWorktree(path: "<git worktree list 가 보여준 경로>")
     · 툴이 없으면(서브에이전트·CLI) 수동:

       git fetch origin main
       git worktree add -b {prefix}/{issue}_{slug} \
           .claude/worktrees/{prefix}/{issue}_{slug} origin/main
       cd .claude/worktrees/{prefix}/{issue}_{slug}

  3. ⚠ 위치 단언 (필수) — 이후 모든 명령은 worktree 안에서 돈다:

       pwd; git rev-parse --show-toplevel; git branch --show-current

     toplevel 이 메인 체크아웃 경로면 **즉시 중단**하고 1 로 돌아간다.
     구현·빌드·커밋·PR·리뷰 반영까지 전부 여기서 한다 — 중간에 메인 체크아웃으로 나가지 않는다

  4. worktree 에는 **추적되는 파일만** 온다. `.env` · `.claude/settings.local.json` 같은
     로컬 전용 파일은 따라오지 않으므로 필요하면 메인 체크아웃에서 복사한다.
     커밋 훅 3개는 `.claude/scripts/` 가 추적 대상이라 그대로 돈다

  ※ 같은 브랜치를 worktree 두 곳에 체크아웃할 수 없다 — git 이 거부한다.
     거부당했다는 것은 **다른 세션이 이미 그 이슈를 잡고 있다**는 신호다.
     이름을 바꿔 새로 만들지 말고 그 worktree 로 들어간다

  ※ 계획보다 **먼저** 분기하는 이유 — worktree 는 미커밋 파일을 데려가지 않는다.
     여기서 분기해야 docs/plans/PLAN-{issue}.md 부터 worktree 안에서 쓰인다

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[Phase 1] 계획   (feature·refactor·fix — chore·quick 은 1줄 메모 후 skip)
  1. 이슈 fetch:
       gh issue view {issue} --json number,title,body,labels,comments,state
     상태가 open 이 아니면 보고 후 선택 게이트. 라벨로 type 추정 근거를 보강한다
     ※ 이슈가 없는 작업(슬러그 진행)이면 이 단계를 건너뛰고 근거 1줄 남긴다

  2. ⚠ 안전 경계 접촉 판정 (이 프로젝트 제1규칙 · 전 타입 필수):
     [safety-boundaries.md](../../rules/context/safety-boundaries.md) S-1~S-6 중
     이 작업이 닿는 조항을 판정한다.
     ├─ 닿음 → 계획서에 **조항 코드 + 어떻게 지킬 것인가**를 명시한다.
     │    S-1 push 대상 · S-2 draft 고정 · S-3 샌드박스 경유 · S-4 시크릿·프롬프트
     │    · S-5 대상 저장소 규약 · S-6 승인 지점·재시도 상한
     └─ 미접촉 → 근거 1줄 남기고 진행

  3. ⚠ 미결 대조 게이트 (전 타입 필수):
     [open-questions.md](../../rules/context/open-questions.md) 에서 작업 관련 미결(Q-1~Q-10) grep.
     ├─ 작업이 미결 항목의 값·판단에 걸림 → **추측으로 채우지 않는다** — 선택 게이트
     │    (가정하고 진행 + 가정을 plan·PR 에 명시 / 확인 후 재개 / 중단)
     └─ 미접촉 → 근거 1줄 남기고 진행

  4. /plan {issue} → docs/plans/PLAN-{issue}.md   (fix 는 원인·수정·검증 3절 약식)

  5. 검토 (feature·refactor 만) — 격리 서브에이전트(subagent_type 명시 · fork 금지: 셀프 승인 방지)에게
     이슈 본문 대비 검토: 빠진 요구 / 이슈에 없는 창작 / 미결 임의 확정 / 안전 경계 누락.
     지적 반영 후 재검토 · 최대 3회 · 같은 중대 지적 잔존 시 선택 게이트

  6. ⚠ 계획 커밋 + push (계획서를 만들었으면 **필수** · 구현 커밋과 섞지 않는다):

       git add docs/plans/PLAN-{issue}.md
       /commit   →  docs(docs): PLAN-{issue} 구현 계획 추가
       git push -u origin {prefix}/{issue}_{slug}

     여기서 커밋·push 하는 이유 4가지 — 하나라도 놓치면 뒤에서 깨진다:
     · PR 본문이 `Plan: docs/plans/PLAN-{issue}.md` 를 링크한다 — 커밋 안 하면 **죽은 링크**다
     · Phase 6 의 worktree 회수가 **추적되지 않는 파일 때문에 거부**된다. force 로 밀면 계획서가 사라진다
     · 원격에 브랜치가 생겨야 다른 세션이 「이 이슈는 이미 누가 잡았다」를 본다 — 중복 작업 방지
     · Phase 0 재진입 감지와 /handoff 가 읽는 것이 이 파일이다. 커밋 안 된 계획은 세션과 함께 증발한다

     ※ chore·quick 은 계획서를 만들지 않으므로 해당 없음

[Phase 2] 구현 + 테스트
  - [architecture.md](../../rules/conventions/architecture.md) 규율 4줄 준수:
    ① 의존은 안쪽으로 (domain 에 Spring·HTTP·GitHub·LLM import 금지)
    ② 진입점 유형별 분리 (web·scheduler·event)
    ③ 능력은 domain 이 선언(능력 이름) · 기술은 adapter/out 이 구현(기술 이름)
    ④ 도메인 간 호출은 application 을 통해서만
  - 🔴 **트랜잭션 안에 대외 호출을 두지 않는다.** 샌드박스 실행은 최대 30분이다 —
    트랜잭션에 들어가면 커넥션이 30분 잡힌다
  - 시각은 `Clock` 주입. `Instant.now()` 직접 호출 금지

  - 테스트 게이트: ./gradlew build   ⚠ CI 미구축(Q-10) — 로컬 build 가 유일한 게이트다
  ⚠ 빌드 판정 (필수) — 파이프로 자른 출력만 보고 성공 판정 금지
     (파이프 종료 코드는 마지막 명령이 덮어쓴다):

     mkdir -p build && ./gradlew build > build/work-build.log 2>&1; echo "exit=$?"; \
       grep -c '^BUILD SUCCESSFUL' build/work-build.log

     exit=0 과 grep 결과 1 이 **둘 다** 나와야 통과다. 하나라도 어긋나면 실패로 취급한다
     ⚠ 로그는 worktree 안(`build/`)에 쓴다. `/tmp/build.log` 같은 공용 경로는 **동시 작업이 서로 덮어쓴다**

  - 커밋: /commit (scope = repository·issue·candidate·agent·pr·support·build·infra·docs·claude ·
    한 커밋에 여러 도메인 금지 · 커밋 시 훅 3개가 돈다 — secret-scan · safety-boundary-check ·
    pre-commit-check. 훅 skip 금지)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[Phase 3] 자가 점검 — git diff 육안   (리뷰 명령 호출 X — 리뷰는 Phase 5.5 한 번)
  의도 외 변경 혼입만 본다: 디버그 잔재(System.out.println) / 무관 파일 / 포맷 노이즈 / 주석 처리된 코드

[Phase 4] 문서 동기화
  - 구조 변경 시 /update-codemaps
  - ⚠ 표면 대조 (필수) — diff 에 아래가 있으면 해당 문서를 같은 브랜치에서 정정한다.
    미해당 시 근거 1줄 skip

    | diff 에 있는 것 | 갱신 대상 |
    |---|---|
    | 도메인 패키지 추가·이동 | codemaps/architecture.md · README.md 구조 절 |
    | 엔티티·컬럼·스키마 | codemaps/data.md |
    | 상태·전이·재시도·필터 규칙 | codemaps/domain.md |
    | 새 환경변수 | .env.example |
    | 새 용어 | rules/context/glossary.md |
    | 구현 중 계획이 바뀜 (범위·설계 변경) | docs/plans/PLAN-{issue}.md — 갱신 후 커밋 |
    | 미결이 닫힘 | rules/context/open-questions.md |

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[Phase 5] PR — /pr 위임   (draft 생성 → Phase 5.5 후 해제)

  base 는 `main` 고정. 제목은 [pr-convention](../../rules/conventions/pr-convention.md) —
  `[#{issue}] <type>(<scope>): <subject>` (이슈 없으면 `[#…]` 생략)

  ⚠ 계약 표면 명시 (2-PR 로 나누지는 않는다 — 단일 저장소·1인이라 과하다):
     diff 에 **도메인 간 인터페이스**(능력 인터페이스 시그니처 · 다른 도메인이 쓰는 UseCase 공개 메서드)
     또는 **DB 스키마**(엔티티 추가·컬럼 변경·마이그레이션) 변경이 포함되면
     PR 본문 Summary 바로 아래에 「계약 표면 변경」 절을 만들어 **무엇이 어떻게 바뀌는지** 적는다.
     리뷰가 계약부터 보도록 만드는 장치다

[Phase 5.5] 코드 리뷰 — **PR 생성 직후 자동 실행** (lifecycle 유일 리뷰 · draft 상태에서 한다)
  ⚠ /pr-review 가 받는 인자는 **PR 번호와 --comments 뿐**이다. 깊이는 인자가 아니라
     이 단계가 **판정해서 실행 구성으로 바꾸는 것**이다. 사람에게 묻지 않고 판정한다.

  1. 신호 수집 — 판정을 기억이 아니라 **명령 결과**로 한다:

       gh pr diff {PR} --name-only
       gh pr view {PR} --json additions,deletions,changedFiles
       + Phase 1-2 의 안전 경계 판정 결과를 함께 놓는다

  2. 깊이 판정 — 위에서부터 **처음 걸리는 줄이 깊이**다 (diff 크기가 아니라 파급 범위):

  | 신호 (하나라도 해당) | 깊이 |
  |---|---|
  | Phase 1-2 가 **안전 경계 접촉**(S-1~S-6)으로 판정 · `adapter/out/**`(GitHub·LLM·샌드박스) · 상태머신 전이 · `db/migration/**` · 능력 인터페이스 시그니처 | **high** |
  | 다중 도메인 · `application/**`(UseCase 흐름) · `.claude/rules/**`·`.claude/skills/**`(이후 모든 작업의 규칙이 바뀐다) | **medium** |
  | 단일 도메인 로컬 로직 · 테스트·문서만 · 대외 호출 없음 | **low** |
  | chore 이고 diff 가 설정·의존성뿐 | **skip** (사유 1줄) |

  3. 깊이별 실행 구성 — 깊이가 **실제로 다른 것을 하게** 만든다:

  | 깊이 | 실행 |
  |---|---|
  | low | `/pr-review {PR}` 단독 |
  | medium | `/pr-review {PR}` + `verifier` 에이전트 (이슈 요구 충족을 읽기 전용으로 독립 검증) |
  | high | `/pr-review {PR}` + **`safety-reviewer` 에이전트 필수** + 반영 후 재검증 (최대 2회) |

  - high 에서는 **구현체를 반드시 열어 본다.** 능력 인터페이스는 깨끗한데 adapter/out 구현체가
    upstream 에 push 하는 식이다 — `safety-reviewer` 가 존재하는 이유가 정확히 이것이다
  - ⚠ 서브에이전트는 백그라운드로 돈다. **결과가 오기 전에 턴을 끝내지 않는다** (스킬 리뷰와 병행은 가능)
  - 판정한 깊이와 **그렇게 판정한 신호**를 결과 보고에 1줄 남긴다. 근거 없는 깊이는 다음 세션이 못 믿는다
  - 깊이를 **올리는 것은 자유, 내리는 것은 사유 필수.** 「diff 가 작아서」는 사유가 아니다

  - findings 처리: 타당 → 수정+재빌드+push / 부적절 → 스킵+사유 1줄
    ⚠ pr-review 의 「수정 전 승인」은 **코멘트 모드**(남이 단 지적) 규칙이다. lifecycle 안에서 자기 PR 의
      findings 를 반영하는 것은 승인 대상이 아니다. 단 **미결(open-questions)에 걸리는 수정**과
      **안전 경계 해석이 갈리는 수정**은 선택 게이트를 띄운다
  - 리뷰 결과는 PR 코멘트로 남기고 (본문 = 상태 선언, 코멘트 = 지적·처리 기록 · 존댓말·표 1개),
    inline findings 는 답글 후 완결 건만 resolve
  - 멈춤 기준: 수정 push 후 새 findings 가 전부 사소하면 종료 / 중대 결함 지속 시 선택 게이트 + draft 유지
  - 완료 후 빌드 green 재확인 → draft 해제

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[리뷰 코멘트 대응] (머지 전 재진입) — /pr-review {PR} --comments 위임
  미해결 스레드 수집 → 타당 = 수정+push / 반려 = 근거 답글 → 답글 후 완결 건만 resolve
  → 설계 결정·안전 경계 해석이 필요한 건은 선택 게이트

[Phase 6] (머지 후) 후속 — 정리까지 해야 끝이다. worktree·브랜치를 남기지 않는다
  0. ⚠ 머지 확인이 선행 조건 — 이것 없이는 아무것도 지우지 않는다:
       gh pr view {pr} --json state,mergedAt,headRefName

  ├─ 이슈 닫기 — gh issue close {issue} --comment "PR #{pr} 머지" (이슈가 있을 때만)
  ├─ 원격 브랜치 삭제 — 머지 시 자동 삭제되지 않았으면 수동:
  │       git push origin --delete {branch}
  ├─ worktree 회수 — 잔존 변경부터 확인한다 (worktree 안에서 git status --porcelain):
  │    ⚠ 여기서 나오는 **추적되지 않는 계획서·문서는 Phase 1-6 을 건너뛴 증거**다.
  │      지우지 말고 커밋·push 한 뒤 회수한다 (계획서는 main 에 남는다 — 삭제 대상이 아니다)
  │    · EnterWorktree 로 들어왔으면 → ExitWorktree(action: "remove")
  │        ↳ 디렉토리와 **브랜치를 함께 지운다.** 아래 로컬 브랜치 삭제는 건너뛴다
  │        ↳ squash 머지 탓에 「원본 브랜치에 없는 커밋」으로 보여 거부될 수 있다.
  │          0 에서 머지를 확인했으면 discard_changes: true 로 재호출
  │    · 수동 분기였으면 → 메인 체크아웃에서:
  │        git worktree remove .claude/worktrees/{prefix}/{issue}_{slug}
  │        git worktree prune
  │    ⚠ 미커밋 변경이 남아 있으면 지우지 않는다 — 목록을 제시하고 선택 게이트.
  │      --force · discard_changes 로 밀어버리지 않는다
  └─ 로컬 브랜치 삭제 (메인 체크아웃 · **worktree 회수 후에만 가능**):
         git pull --ff-only origin main
         git branch -d {branch}
       ↳ **Squash Merge 라 `-d` 가 "not fully merged" 로 거부하는 것이 정상이다** —
         squash 는 새 커밋을 만들어 브랜치 커밋이 main 에 그대로 남지 않는다.
         0 에서 머지를 확인했으면 git branch -D {branch} 로 지운다
       ↳ ExitWorktree(remove) 로 회수했으면 브랜치는 이미 없다 —
         git branch --list {branch} 가 비어 있으면 skip

  정리 결과(worktree 제거 · 로컬/원격 브랜치 삭제)를 결과 보고에 남긴다.
  하나라도 남겼으면 **남긴 이유와 경로**를 적는다 — 조용히 쌓이면 다음 /work 가 재진입으로 오판한다

[결과 보고]
  PR 링크 · 안전 경계 판정 · 미결 대조 결과 · 리뷰 결과(반영/코멘트/스킵 사유)
  · 문서 동기화 · **정리 결과(worktree·브랜치)** · 다음 단계
```

## 실패 처리

| 상황 | 동작 |
|------|------|
| 이슈 식별 실패 | 에러 + `/work <issue> <type>` 안내 · 슬러그 진행 여부 확인 |
| **push·PR 이 403** | 토큰 스코프 문제로 오진하지 않는다 — **gh 활성 계정**을 먼저 본다. `gh auth switch --user smileboy0014` |
| **메인 체크아웃에서 작업 중임을 발견** | 즉시 중단 — 변경분을 커밋·stash 로 옮겨 worktree 에서 재개. 메인 체크아웃에 작업물을 남기지 않는다 |
| 브랜치가 이미 다른 worktree 에 체크아웃 (git 거부) | **중복 작업 신호** — 새로 만들지 않고 그 worktree 로 들어가 재진입 처리 |
| worktree 제거 실패 (미커밋 변경 잔존) | 강제 삭제 금지 — 잔존 목록 제시 후 선택 게이트 |
| `git branch -d` 가 "not fully merged" 로 거부 | **Squash Merge 의 정상 동작.** `gh pr view` 로 머지를 확인한 뒤에만 `-D`. 확인 전에는 지우지 않는다 |
| 계획서가 커밋되지 않은 채 남아 있음 | Phase 1-6 누락 — worktree 회수가 거부되거나 계획서가 유실된다. 커밋·push 후 진행 |
| worktree 는 지웠는데 브랜치가 남음 | `git worktree prune` 후 `git branch -d`(필요 시 `-D`). 남은 브랜치는 다음 /work 를 재진입으로 오판하게 만든다 |
| type 미지정 + 컨펌 응답 없음 | 진행 불가 — 추정만으로 진행 X |
| **안전 경계(S-1~S-6) 접촉** | 계획서에 조항·준수 방법 명시 없이 Phase 2 로 넘어가지 않는다 |
| 미결(open-questions) 접촉 | 추측 금지 — 선택 게이트 (가정 명시 진행 / 확인 대기 / 중단) |
| 빌드·테스트 실패 | Phase 2 중단 — 원인 수정 후 재검증. **파이프 출력만 보고 통과 판정 금지** |
| 시크릿 훅 차단 (secret-scan) | 값을 지우고 환경변수·`<REPLACE_WITH_SECRET_MANAGER>` 로 대체. 훅 skip 금지 |
| 안전 경계 훅 차단 (safety-boundary-check) | 위반 수정 — `safety-ok` 예외는 **사유 필수**, 사유 없는 예외는 반려 |
| `./gradlew` 부재 | 빌드 게이트 없음 — 「통과」라고 하지 않는다. 사실을 보고에 남긴다 |
| 계획 검토 3회 소진 | 선택 게이트 (미해결 지적 목록 제시) |
| 리뷰 깊이 판정이 갈림 | **높은 쪽으로 올린다.** 내리려면 사유를 보고에 남긴다 — 「diff 가 작아서」는 사유가 아니다 |
| high 인데 `safety-reviewer` 가 없음 | 임의 대체 금지 — 그 사실을 보고에 남기고 draft 를 유지한다 |
| **참조한 스킬·에이전트가 없음** | **임의로 대체하지 않는다** — 이름이 바뀐 것인지 확인하고, 없으면 그 사실을 보고에 남긴다. 조용히 다른 수단으로 갈음하면 규정 단계가 생략된 채 통과한다 |

## 출력 형식

> 결과 보고는 **완료된 것 + 다음에 필요한 것**만. 상세는 PR 본문·PLAN 에 위임.

```
{✅|🚨|⚡|⏩} /work {issue} {type} — 완료

이슈: #{issue} — {title}
브랜치: {prefix}/{issue}_{slug} → main
worktree: .claude/worktrees/{prefix}/{issue}_{slug}
PLAN: docs/plans/PLAN-{issue}.md (커밋됨) / 없음(chore·quick)
PR: {URL}

| 단계 | 상태 |
|------|------|
| 0.5 worktree 격리 | ✅ |
| 1 계획 (+안전 경계·미결 대조 · PLAN 커밋) | ✅ / skip |
| 2 구현·테스트 (gradlew build) | ✅ |
| 3 자가 점검 | ✅ |
| 4 문서 동기화 | ✅ / skip (근거) |
| 5 PR | ✅ |
| 5.5 코드 리뷰 ({depth} — {신호}) | ✅ / skip |

안전 경계: {접촉 조항 + 준수 방법 / 미접촉}
미결: {걸린 Q 항목 + 처리 / 미접촉}

다음: PR 검토 → 머지 → /work {issue} {type} 재호출 (Phase 6)
```
