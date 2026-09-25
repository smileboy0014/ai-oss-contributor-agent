# 훅 시스템

> **메모리는 잊는다. 하네스는 잊지 않는다.**
> 강제력 위계: 훅(기계적 차단, 100%) > 스킬(워크플로우, ~80%) > CLAUDE.md·memory(참고, ~50%).

구현은 전부 [`.claude/scripts/`](../scripts/) 에 있고, **등록처가 둘**이다.

| 등록처 | 언제 도나 | 무엇이 걸려 있나 |
|---|---|---|
| **git `.githooks/pre-commit`** | **모든** `git commit` — 사람·Claude·IDE·worktree | 차단형 2개 |
| [`.claude/settings.json`](../settings.json) | Claude 의 도구 호출 전후 | 피드백형 4개 · Stop 훅 |

## 등록 현황

### git 훅 — 차단형

| 스크립트 | 하는 일 |
|---|---|
| [`secret-scan.sh`](../scripts/secret-scan.sh) | 시크릿 패턴·`.env` 스테이징 **차단** |
| [`safety-boundary-check.sh`](../scripts/safety-boundary-check.sh) | 안전 경계 S-1~S-4 정적 위반 **차단** |

[`pre-commit-check.sh`](../scripts/pre-commit-check.sh)(`./gradlew check`)는 **여기 없다** —
CI 로 옮겼다(Q-10). 스크립트는 남아 있고 CI 가 호출한다.

### Claude 훅 — 피드백형

| 이벤트 | matcher | 스크립트 | 하는 일 |
|---|---|---|---|
| `PostToolUse` | `Bash\|Grep\|Glob` | [`output-truncator.sh`](../scripts/output-truncator.sh) | 50K자 초과 출력 축약 |
| `PostToolUseFailure` | `Edit\|Write` | [`edit-recovery.sh`](../scripts/edit-recovery.sh) | 실패 패턴별 복구 가이드 |
| | `Read` | [`large-file-recovery.sh`](../scripts/large-file-recovery.sh) | 대용량·바이너리 파일 대안 안내 |
| | `` (전체) | [`tool-failure-tracker.sh`](../scripts/tool-failure-tracker.sh) | 60초 내 반복 실패 감지 → 전략 전환 유도 |
| `Stop` | `` | [`impl-test-loop.sh`](../scripts/impl-test-loop.sh) | 변경분이 있으면 `./gradlew test` |

## 차단형 2개 — 커밋 시점

순서가 의미를 갖는다. **싼 검사가 먼저** 돈다.

```
secret-scan (grep)  →  safety-boundary-check (grep)
```

### ⚠️ 왜 git 훅으로 옮겼나 (2026-09-25 · #27)

원래 이 셋은 `PreToolUse` `matcher: "Bash(git commit:*)"` 에만 걸려 있었다. **두 경로로 샜다.**

| 구멍 | 결과 |
|---|---|
| 사람이 IDE·터미널에서 직접 커밋 | Claude 를 거치지 않으므로 **아무것도 돌지 않는다** |
| `cd <path> && git commit …` · `git -C … commit` | 접두사 매칭이라 **빗나간다** — worktree 작업에서 실제로 발생 |

`#35` 리뷰에서 「훅이 실행된 흔적이 없다」로 드러났다. 아래 「훅이 조용히 죽는 경우가 가장 나쁘다」가
**우리에게 일어난 것**이고, 매처 하나에 게이트 전체를 건 것이 원인이었다.

스크립트는 stdin 을 읽지 않고 `git diff --cached` 만 보므로 수정 없이 그대로 git 훅이 된다.

```bash
git config core.hooksPath .githooks   # 클론·worktree 추가 후 1회 — 커밋되지 않는 로컬 설정이다
```

`--no-verify` 우회는 **CI 가 같은 스캔 2종을 다시 돌려** 잡는다.

### secret-scan.sh

탐지 패턴: `AKIA…` · `ghp_` · `gho_` · `github_pat_` · `xox[baprs]-` · `sk-ant-` · PEM private key,
그리고 `.env`(단 `.env.example` 제외) 스테이징.

`<REPLACE_WITH_SECRET_MANAGER>` 플레이스홀더와 `.env.example` 은 제외한다.

### safety-boundary-check.sh

[`safety-boundaries.md`](../rules/context/safety-boundaries.md) 중 **문자열로 잡히는 것**만 본다.

| 조항 | 잡는 패턴 |
|---|---|
| S-1 | push 호출 근처의 upstream 좌표 |
| S-2 | `draft(false)` · `merge(` · `readyForReview` · `requestReviewers` |
| S-3 | `Runtime.getRuntime().exec` · `new ProcessBuilder` · `/var/run/docker.sock` |
| S-4 | 로그 인자의 `token`·`apiKey`·`secret`·`password` |

의도된 예외는 **사유와 함께** 남긴다. 위반 라인 또는 바로 윗줄에 달면 통과한다.

```java
// safety-ok: 샌드박스 컨테이너 자체를 띄우는 지점 — 대상 저장소 코드가 아니다
Process p = new ProcessBuilder("docker", "run", ...).start();
```

**사유 없는 `safety-ok` 는 통과시키지 않는다.**

### ⚠️ 훅 통과 ≠ 합격

훅은 문자열만 본다. 아래는 **grep 으로 잡히지 않는다.**

- 포트/구현체를 따라가야 드러나는 upstream push
- 설정으로 draft 가 뒤집히는 경로
- 프롬프트 조립 지점에서 시크릿 배제가 빠진 것
- 재시도 상한·승인 게이트 우회

이건 [`agents/safety-reviewer.md`](../agents/safety-reviewer.md) 와 사람이 본다.

## 피드백형 4개

차단하지 않고 컨텍스트를 주입한다. 실패하거나 느려도 작업을 막지 않는 것이 설계 의도다.

## Stop 훅 — impl-test-loop.sh

턴이 끝날 때 변경된 소스가 있으면 테스트를 돌린다.

- 대상: 미커밋 변경 ∪ 최근 30분 내 수정된 `src/**/*.java`
- 연속 3회 실패 → 수동 개입 유도 (무한 루프 방지)
- **한 건도 실행하지 않았으면 「통과」라고 하지 않는다** — `src/test` 부재·`gradlew` 부재는 사유를 표시하고 건너뛴다

이 원칙이 중요한 이유: 「테스트 통과」라는 거짓 신호 하나가 게이트 전체를 무의미하게 만든다.

## 훅 추가하기

1. `.claude/scripts/` 에 스크립트 작성 (`#!/bin/bash` + 역할 주석 + `chmod +x`)
2. `bash -n` 으로 문법 확인
3. 등록처를 고른다 — **커밋을 막아야 하면 `.githooks/pre-commit`**, Claude 도구 흐름에 끼우는 것이면 `.claude/settings.json`
4. 위반 코드와 정상 코드 양쪽으로 시뮬레이션

⚠️ **차단형을 `.claude/settings.json` 에만 걸지 않는다.** 사람이 직접 커밋하는 경로가 통째로 빠진다.

**오탐 최소화가 최우선이다.** 규칙이 너무 넓으면 개발이 멈추고, 결국 `--no-verify` 로 우회하게 된다.
의심스러우면 훅 대신 스킬 체크리스트에 먼저 두고, 패턴이 명확해지면 승격한다 —
[`/antipattern`](../skills/antipattern/SKILL.md).

## 훅 디버깅

```bash
echo '{}' | .claude/scripts/secret-scan.sh; echo "exit=$?"
bash -n .claude/scripts/safety-boundary-check.sh
```

훅이 조용히 죽는 경우가 가장 나쁘다 — 검사가 안 돌았는데 통과한 것처럼 보인다.
그래서 모든 차단형 스크립트는 **「검사를 실행하지 않았다」를 명시적으로 출력**한다.
