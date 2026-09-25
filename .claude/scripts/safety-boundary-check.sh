#!/bin/bash
# PreToolUse(Bash(git commit:*)) hook: 안전 경계(safety-boundaries.md S-1~S-4) 정적 검사
# 스테이징된 src/**/*.java 만 본다. 훅이 잡는 건 문자열뿐이고 호출 그래프는 리뷰가 본다.
# 의도된 예외는 위반 라인 또는 바로 윗줄에 「safety-ok: <사유>」를 남기면 통과한다.

set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$ROOT" || exit 0

files=$(git diff --cached --name-only --diff-filter=ACMR | grep -E '^src/.*\.java$' || true)
if [ -z "$files" ]; then
  exit 0
fi

violations=""
found=0

# 주석 라인인가 (//, *, /*)
is_comment() {
  echo "$1" | grep -qE '^[[:space:]]*(//|\*|/\*)'
}

# 사유가 붙은 safety-ok 인가 — 「safety-ok:」 뒤에 실제 글자가 있어야 한다
has_waiver() {
  echo "$1" | grep -qE 'safety-ok:[[:space:]]*[^[:space:]]+'
}

# $1=파일 $2=조항 $3=정규식 $4=위험 설명 $5=대안
check() {
  local f="$1" clause="$2" regex="$3" why="$4" alt="$5"
  local content lineno linetext prevtext
  content=$(git show ":${f}" 2>/dev/null) || return 0

  while IFS=: read -r lineno linetext; do
    [ -z "$lineno" ] && continue
    is_comment "$linetext" && continue
    has_waiver "$linetext" && continue
    # 윗줄 waiver 는 그 줄이 「단독 주석」일 때만 인정한다.
    # 코드 라인의 인라인 waiver 는 자기 줄에만 적용된다 — 아니면 다음 줄까지 새어 나간다.
    prevtext=$(echo "$content" | sed -n "$((lineno-1))p")
    if is_comment "$prevtext" && has_waiver "$prevtext"; then
      continue
    fi

    violations="${violations}\n  ❌ [${clause}] ${f}:${lineno}\n     ${linetext#"${linetext%%[![:space:]]*}"}\n     왜 위험한가: ${why}\n     대안: ${alt}"
    found=$((found+1))
  done <<< "$(echo "$content" | grep -nE "$regex" || true)"
}

for f in $files; do
  is_test=0
  case "$f" in
    src/test/*) is_test=1 ;;
  esac

  # ── S-1. 원본 저장소 쓰기 ────────────────────────────────────
  check "$f" "S-1" \
    'setRemote\([^)]*(getUrl\(\)|upstream|Upstream|UPSTREAM)' \
    "원본(upstream) 좌표로 push 하면 남의 저장소 히스토리를 오염시킵니다. 되돌릴 수 없습니다." \
    "쓰기 대상은 사용자 Fork 뿐입니다. push 직전 원격 owner 가 GITHUB_FORK_OWNER 와 같은지 단언하세요."

  check "$f" "S-1" \
    '\.push\(\)[^;]*(upstream|getUrl\(\))' \
    "원본 저장소로의 push 경로로 의심됩니다." \
    "Fork 좌표(fork.getPushUrl())만 사용하고, owner 일치 어설션을 두세요."

  # ── S-2. Draft 고정 · 자동 머지 금지 ─────────────────────────
  check "$f" "S-2" \
    '(setDraft\([[:space:]]*false|draft\([[:space:]]*false|"draft"[[:space:]]*:[[:space:]]*false)' \
    "draft 가 아닌 PR 은 메인테이너 리뷰 큐에 즉시 들어갑니다. 검증되지 않은 AI 코드는 스팸으로 취급됩니다." \
    "PR 생성은 draft=true 로 고정합니다. 해제는 사람이 GitHub UI 에서 합니다."

  check "$f" "S-2" \
    '(mergePullRequest|readyForReview|setReadyForReview|requestReviewers|requestReview\()' \
    "자동 머지·리뷰 요청·리뷰어 지정은 사람의 승인 지점을 우회합니다." \
    "이 호출을 제거하세요. 제품 정의상 여기는 사람이 하는 일입니다."

  # ── S-3. 샌드박스 밖 실행 금지 ───────────────────────────────
  # 테스트 코드도 검사한다 (2026-09-25 · #4). 원래는 src/test 를 통째로 건너뛰었는데,
  # 그러면 스프링을 쓰지 않는 평범한 JUnit 테스트가 ProcessBuilder 로 호스트에서 대상
  # 저장소를 빌드하거나 docker.sock 을 마운트해도 아무 장치가 막지 않는다.
  #
  # ⚠ 「자동 스위트가 컨테이너를 전혀 띄우지 않는다」는 뜻이 아니다. 인프라 컨테이너
  #   (Testcontainers PostgreSQL)는 Q-2b 가 정한 의도된 예외다. 막는 것은 **샌드박스**
  #   — 즉 신뢰할 수 없는 대상 저장소 코드를 호스트에서 실행하는 경로다.
  #   Testcontainers 는 Java API 를 쓰지 프로세스를 띄우지 않으므로 걸리지 않는다.
  #
  # 우리 저장소의 스크립트(.claude/scripts/*.sh)를 테스트에서 돌리는 것은 S-3 의 보호
  # 대상이 아니다. 그 경우는 // safety-ok: <사유> 로 예외 처리한다.
  if [ "$is_test" -eq 1 ]; then
    exec_why="테스트가 호스트에서 대상 저장소 코드를 실행하면 개발자 머신이 장악됩니다. 샌드박스 컨테이너는 자동 스위트에서 띄우지 않습니다 — Q-9. (인프라 컨테이너인 Testcontainers PostgreSQL 은 Q-2b 가 정한 예외입니다.)"
    exec_alt="CodeSandbox 페이크로 갈음하세요. 컨테이너 제어 자체를 검증해야 하면 docker 호출을 기록하는 대역을 씁니다. 대상 저장소가 아니라 우리 저장소의 스크립트를 돌리는 것이라면 S-3 대상이 아니므로 // safety-ok: <사유> 로 예외 처리하세요."
  else
    exec_why="대상 저장소 코드는 신뢰할 수 없습니다. 빌드 스크립트는 임의 코드 실행이며, 호스트에서 돌리면 머신이 장악됩니다."
    exec_alt="CodeSandbox 능력 인터페이스를 통해 Docker 샌드박스 안에서만 실행하세요."
  fi

  check "$f" "S-3" \
    '(Runtime\.getRuntime\(\)\.exec|new[[:space:]]+ProcessBuilder)' \
    "$exec_why" \
    "$exec_alt"

  check "$f" "S-3" \
    '/var/run/docker\.sock' \
    "Docker 소켓 마운트는 컨테이너 탈출 경로입니다. 샌드박스의 의미가 사라집니다." \
    "소켓을 마운트하지 마세요. 샌드박스는 호스트 Docker 데몬을 볼 수 없어야 합니다."

  # ── S-4. 시크릿 로깅 금지 ────────────────────────────────────
  check "$f" "S-4" \
    'log\.(trace|debug|info|warn|error)\([^)]*([Tt]oken|apiKey|ApiKey|API_KEY|[Ss]ecret|[Pp]assword|credential)' \
    "토큰·키가 로그로 나가면 회수할 수 없습니다. 로그는 수집기·백업으로 복제됩니다." \
    "값을 로그에 넣지 말고, 불가피하면 앞 4자 + *** 로 마스킹하세요. logging.md 참조."
done

if [ "$found" -gt 0 ]; then
  echo "❌ 안전 경계 위반 의심 ${found}건 — 커밋을 중단합니다."
  printf "%b\n" "$violations"
  echo ""
  echo "근거: .claude/rules/context/safety-boundaries.md"
  echo "의도된 예외라면 해당 라인 또는 바로 윗줄에 사유를 남기세요:"
  echo "  // safety-ok: <왜 안전한지 — 사유 없는 safety-ok 는 통과하지 않습니다>"
  exit 1
fi

echo "✅ 안전 경계 검사 통과 (정적 탐지분)"
exit 0
