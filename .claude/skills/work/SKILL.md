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
  1. 인자 파싱 (이슈 미지정 시 브랜치명에서 추출)
  1.5 재진입 감지 — 처음부터 다시 돌지 않는다:
      ├─ PR 머지됨 → Phase 6 직행
      ├─ PR open → 리뷰 코멘트 있으면 [리뷰 코멘트 대응] / 빌드 적색이면 Phase 2 회귀
      ├─ 브랜치·커밋 존재 → 기존 산출물 입력으로 Phase 2 이어서
      └─ docs/plans/PLAN-{issue}.md 만 존재 → 요구 변경 없으면 채택 후 Phase 2
  2. type 미지정 → gh issue view 후 추정 → ⚠ AskUserQuestion 강제
     (추정 type 첫 옵션 + Recommended + 근거)
  3. TodoWrite 로 해당 type 의 phase 들을 진행 추적 등록

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

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[브랜치 분기]
  git fetch origin main
  git checkout -b {prefix}/{issue}_{slug} origin/main
  ※ 동일 브랜치 존재 = 재진입 — 이어서 작업. 다른 세션과 충돌 우려 시 git worktree 수동 분기

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
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

     ./gradlew build > /tmp/build.log 2>&1; echo "exit=$?"; grep -c '^BUILD SUCCESSFUL' /tmp/build.log

     exit=0 과 grep 결과 1 이 **둘 다** 나와야 통과다. 하나라도 어긋나면 실패로 취급한다

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

  ⚠ 메타데이터 승계 (이슈가 있으면 필수) — /pr 8단계:
     이슈의 **라벨·마일스톤·프로젝트를 PR 에 그대로 옮긴다.** 사람이 나중에 붙이는 것에 기대지 않는다.
     붙였는지 `gh pr view --json labels,milestone` 로 **확인까지** 한다 —
     저장소에 없는 라벨이면 gh 가 조용히 실패한다.
     이슈가 없는 작업이면 건너뛰고 근거 1줄을 결과 보고에 남긴다

[Phase 5.5] 코드 리뷰 — /pr-review {PR} 위임   (lifecycle 유일 리뷰 · chore skip)
  ⚠ 스킬이 받는 인자는 **PR 번호와 --comments 뿐**이다. 깊이는 인자가 아니라 **이 세션이 정하는 것**이고,
     diff 크기가 아니라 파급 범위로 정한다. 정한 근거를 결과 보고에 1줄 남긴다.

  | 위험도 신호 | 깊이 | 스킬 외에 더 하는 것 |
  |------------|------|---------------------|
  | 단일 도메인 로컬 로직 · 대외 호출 없음 | low | 없음 |
  | 다중 파일 · UseCase 흐름 · 단일 도메인 | medium | 없음 |
  | **안전 경계 접촉**(S-1~S-6) · **대외 호출 경로**(GitHub·LLM·샌드박스) · **상태머신 전이** | high | 격리 서브에이전트 동반 검토 + 반영 후 재검증 |

  - high 에서는 **구현체를 반드시 열어 본다.** 인터페이스만 보면 안전 경계 위반이 드러나지 않는다 —
    능력 인터페이스는 깨끗한데 adapter/out 구현체에서 upstream 에 push 하는 식이다
  - ⚠ 서브에이전트는 백그라운드로 도므로 **결과가 오기 전에 턴을 끝내지 않는다.** 스킬 리뷰와 병행해도 된다
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

[Phase 6] (머지 후) 후속
  ├─ 이슈 닫기 — gh issue close {issue} --comment "PR #{pr} 머지" (이슈가 있을 때만)
  ├─ 원격 브랜치 삭제 — gh pr 머지 시 자동 삭제되지 않았으면 수동
  └─ 로컬 정리 — git checkout main && git pull && git branch -d {branch}

[결과 보고]
  PR 링크 · 안전 경계 판정 · 미결 대조 결과 · 리뷰 결과(반영/코멘트/스킵 사유)
  · 문서 동기화 · 다음 단계
```

## 실패 처리

| 상황 | 동작 |
|------|------|
| 이슈 식별 실패 | 에러 + `/work <issue> <type>` 안내 · 슬러그 진행 여부 확인 |
| type 미지정 + 컨펌 응답 없음 | 진행 불가 — 추정만으로 진행 X |
| **안전 경계(S-1~S-6) 접촉** | 계획서에 조항·준수 방법 명시 없이 Phase 2 로 넘어가지 않는다 |
| 미결(open-questions) 접촉 | 추측 금지 — 선택 게이트 (가정 명시 진행 / 확인 대기 / 중단) |
| 빌드·테스트 실패 | Phase 2 중단 — 원인 수정 후 재검증. **파이프 출력만 보고 통과 판정 금지** |
| 시크릿 훅 차단 (secret-scan) | 값을 지우고 환경변수·`<REPLACE_WITH_SECRET_MANAGER>` 로 대체. 훅 skip 금지 |
| 안전 경계 훅 차단 (safety-boundary-check) | 위반 수정 — `safety-ok` 예외는 **사유 필수**, 사유 없는 예외는 반려 |
| `./gradlew` 부재 | 빌드 게이트 없음 — 「통과」라고 하지 않는다. 사실을 보고에 남긴다 |
| 계획 검토 3회 소진 | 선택 게이트 (미해결 지적 목록 제시) |
| **참조한 스킬·에이전트가 없음** | **임의로 대체하지 않는다** — 이름이 바뀐 것인지 확인하고, 없으면 그 사실을 보고에 남긴다. 조용히 다른 수단으로 갈음하면 규정 단계가 생략된 채 통과한다 |

## 출력 형식

> 결과 보고는 **완료된 것 + 다음에 필요한 것**만. 상세는 PR 본문·PLAN 에 위임.

```
{✅|🚨|⚡|⏩} /work {issue} {type} — 완료

이슈: #{issue} — {title}
브랜치: {prefix}/{issue}_{slug} → main
PR: {URL}

| 단계 | 상태 |
|------|------|
| 1 계획 (+안전 경계·미결 대조) | ✅ / skip |
| 2 구현·테스트 (gradlew build) | ✅ |
| 3 자가 점검 | ✅ |
| 4 문서 동기화 | ✅ / skip (근거) |
| 5 PR (+메타데이터 승계) | ✅ |
| 5.5 코드 리뷰 ({depth} — {신호}) | ✅ / skip |

안전 경계: {접촉 조항 + 준수 방법 / 미접촉}
미결: {걸린 Q 항목 + 처리 / 미접촉}

다음: PR 검토 → 머지 → /work {issue} {type} 재호출 (Phase 6)
```
