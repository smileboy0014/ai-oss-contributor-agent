#!/bin/bash
# git pre-commit 훅 + CI: 안전 경계(safety-boundaries.md S-1~S-4) 정적 검사
# src/**/*.java 만 본다. 훅이 잡는 건 문자열뿐이고 호출 그래프는 리뷰가 본다.
# 의도된 예외는 위반 라인 또는 바로 윗줄에 「safety-ok: <사유>」를 남기면 통과한다.
#
# SCAN_MODE=staged (기본) — 스테이징분만. git 훅이 쓴다
# SCAN_MODE=tree          — 추적 파일 전체. CI 가 쓴다

set -uo pipefail

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$ROOT" || exit 0

# ── 검사 범위 ─────────────────────────────────────────────────
# staged (기본) — git 훅. 스테이징된 것만 본다
# tree          — CI. 추적 파일 전체를 본다. --no-verify 우회가 여기서 잡힌다
SCAN_MODE="${SCAN_MODE:-staged}"

list_files() {
  if [ "$SCAN_MODE" = "tree" ]; then
    git ls-files
  else
    git diff --cached --name-only --diff-filter=ACMR
  fi
}

read_file() {
  if [ "$SCAN_MODE" = "tree" ]; then
    cat "$1" 2>/dev/null
  else
    git show ":$1" 2>/dev/null
  fi
}

files=$(list_files | grep -E '^src/.*\.java$' || true)
if [ -z "$files" ]; then
  # 조용히 통과하지 않는다 — 「검사가 안 돌았는데 통과한 것처럼 보이는 것」이 가장 나쁘다
  echo "ℹ️  검사 대상 java 파일이 없습니다 (SCAN_MODE=${SCAN_MODE}) — 안전 경계 검사를 실행하지 않았습니다."
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
  content=$(read_file "$f") || return 0

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

  # ── S-3. 샌드박스 밖 실행 금지 (테스트 코드는 제외) ──────────
  if [ "$is_test" -eq 0 ]; then
    check "$f" "S-3" \
      '(Runtime\.getRuntime\(\)\.exec|new[[:space:]]+ProcessBuilder)' \
      "대상 저장소 코드는 신뢰할 수 없습니다. 빌드 스크립트는 임의 코드 실행이며, 호스트에서 돌리면 머신이 장악됩니다." \
      "CodeSandbox 능력 인터페이스를 통해 Docker 샌드박스 안에서만 실행하세요."

    check "$f" "S-3" \
      '/var/run/docker\.sock' \
      "Docker 소켓 마운트는 컨테이너 탈출 경로입니다. 샌드박스의 의미가 사라집니다." \
      "소켓을 마운트하지 마세요. 샌드박스는 호스트 Docker 데몬을 볼 수 없어야 합니다."
  fi

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

echo "✅ 안전 경계 검사 통과 (정적 탐지분 · SCAN_MODE=${SCAN_MODE} · 파일 $(echo "$files" | wc -l | tr -d ' ')개)"
exit 0
