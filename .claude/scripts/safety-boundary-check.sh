#!/bin/bash
# git pre-commit 훅 + CI: 안전 경계(safety-boundaries.md S-1~S-4) 정적 검사
# src/**/*.java 만 본다. 훅이 잡는 건 문자열뿐이고 호출 그래프는 리뷰가 본다.
# 의도된 예외는 위반 라인 또는 바로 윗줄에 「safety-ok: <사유>」를 남기면 통과한다.
#
# SCAN_MODE=staged (기본) — 스테이징분만. git 훅이 쓴다
# SCAN_MODE=tree          — 추적 파일 전체. CI 가 쓴다

set -uo pipefail

# 🔴 스크립트 전체를 **바이트 지향**으로 고정한다 — #75.
#
#   UTF-8 로케일의 grep 은 유효하지 않은 바이트를 만나면 **그 뒤쪽을 매칭에서 버린다.**
#   여기서는 그것이 **위반을 놓치는** 방향이다 — 한 줄 안에서 부정 바이트 **뒤에** 오는
#   ProcessBuilder·docker.sock 이 조용히 통과한다.
#
#   ⚠ 이 스크립트의 위반 패턴은 전부 **비앵커**라, 구멍에 그대로 노출된다
#   (앵커를 쓰는 패턴만 원리적으로 면역이다 — PLAN-75 §1).
#
#   ⚠ secret-scan.sh 와 **같은 구멍이고 같은 이유**다. #64 가 sed 에만 LC_ALL=C 를
#   붙이고 grep 에는 안 붙여 #75 가 났다. 여기서 secret-scan 만 고치면 그 모양을
#   한 번 더 만든다 — 그래서 같이 고친다(docs/plans/PLAN-75.md §2-5).
#
#   ⚠ 아래 두 곳은 반대 방향(과차단)이라 급하지 않지만 같은 원인이다 —
#   `safety-ok:` 매칭과 직전 줄을 뽑는 sed 가 부정 바이트 줄에서 실패하면
#   **면제를 못 읽어** 무고하게 빨개진다. 같은 export 가 둘 다 닫는다.
#
# 🕳 **secret-scan.sh 에는 있는 기동 자가 점검이 여기에는 없다 — 의도적이다.**
#   그쪽은 조용한 0건이 **시크릿 유출**(되돌릴 수 없다)이라 fail-closed 가 값을 한다.
#   여기서 조용한 0건은 정적 탐지 하나를 놓치는 것이고, 그 층은 애초에 보조다 —
#   「훅 통과 ≠ 합격」이고 호출 그래프 판정은 safety-reviewer 와 사람이 한다.
#   점검을 더하면 **커밋을 막는 오탐 표면만 두 배**가 된다.
#   ⚠ 그래서 이 export 가 지워지면 **아무도 모른다.** 그것이 여기 남은 우회다.
export LC_ALL=C

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

  # 🔴 #22 이후 쓰기 경로는 JGit 이 아니라 REST 다. 위 두 패턴은 setRemote·push() 라는
  #    **JGit 모양만** 보므로 Git Data API 경로를 전혀 보지 못한다. 그 공백을 메운다.
  #
  # 🕳 한계를 먼저 적는다 — 새는 방향부터.
  #    ① 변수명이 upstream 이 아니면 안 걸린다. **거부목록**이고,
  #       testing-philosophy.md 가 「거부목록으로 방어하지 않는다」고 적은 그 방식이다
  #    ② 이 스크립트는 src/**/*.java 의 **문자열만** 본다. 실제 위험 표면은 호출 그래프라
  #       파일 범위와 무관하게 안 잡힌다
  #    실질 방어는 ⓐ GitHubWriteClient 의 런타임 owner 어설션과
  #    ⓑ ForkPublishArchitectureTest 의 여집합 ArchUnit 이다. 이 패턴은 **보조**다 —
  #    방어로 세면 거짓 안전감이 된다.
  #
  # ⚠ `\.` 로 메서드 호출 형태를 요구한다. 앵커가 없으면 input( · softDelete( 같은
  #   평범한 이름이 put( · delete( 를 품어 무고하게 빨개진다.
  #
  # ⚠ **첫 인자**만 본다. 인자 전체를 훑으면 `post(fork, "merge-upstream", …)` 이 걸리는데
  #   그것은 GitHub 엔드포인트 **이름**이고 쓰기 대상은 Fork 다 — 실측으로 걸렸고,
  #   safety-ok 로 덮는 대신 패턴을 좁혔다. 위험한 모양은 **쓰기 대상이 upstream 인 것**이고
  #   그것은 첫 인자 자리에 온다. 덮어서 통과시키면 다음 사람이 그 waiver 를 근거로 삼는다.
  check "$f" "S-1" \
    '\.(post|patch|put|delete)\([[:space:]]*(upstream|Upstream|UPSTREAM)' \
    "원본(upstream) 좌표로 REST 쓰기를 보내는 경로로 의심됩니다. 남의 저장소 히스토리는 되돌릴 수 없습니다." \
    "쓰기 대상은 ForkRef 로만 만드세요. GitHubWriteClient 가 경로 대신 (owner, name, subPath) 를 받아 매 호출 직전 owner 를 단언합니다."

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

echo "✅ 안전 경계 검사 통과 (정적 탐지분 · SCAN_MODE=${SCAN_MODE} · 파일 $(echo "$files" | wc -l | tr -d ' ')개)"
exit 0
