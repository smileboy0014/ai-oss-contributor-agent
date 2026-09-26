#!/bin/bash
# git pre-commit 훅 + CI: 시크릿 커밋 차단
# 토큰·키 패턴을 찾고, .env 실파일이 올라갔는지 본다.
# 하나라도 걸리면 exit 1 로 커밋(또는 CI)을 막는다.
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

files=$(list_files)
if [ -z "$files" ]; then
  echo "ℹ️  검사 대상 파일이 없습니다 (SCAN_MODE=${SCAN_MODE}) — 시크릿 검사를 실행하지 않았습니다."
  exit 0
fi

violations=""
found=0

# ── ① .env 실파일 스테이징 검사 ────────────────────────────────
for f in $files; do
  base=$(basename "$f")
  case "$base" in
    .env.example) continue ;;
    .env|.env.*)
      violations="${violations}\n  ❌ ${f}\n     → .env 계열 실파일은 커밋 대상이 아닙니다. .gitignore 에 두고 값은 Secret Manager·환경변수로 주입하세요.\n     → git restore --staged ${f}"
      found=$((found+1))
      ;;
  esac
done

# ── ② 토큰·키 패턴 검사 ────────────────────────────────────────
# 이름=설명=정규식 (자기 자신과 .env.example 은 검사 제외)
scan_pattern() {
  local label="$1"
  local advice="$2"
  local regex="$3"
  local f="$4"

  local hits
  hits=$(read_file "$f" | grep -nE "$regex" | grep -v '<REPLACE_WITH_SECRET_MANAGER>' || true)
  if [ -n "$hits" ]; then
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      local lineno="${line%%:*}"
      violations="${violations}\n  ❌ ${label} — ${f}:${lineno}\n     → ${advice}"
      found=$((found+1))
    done <<< "$hits"
  fi
}

for f in $files; do
  # 바이너리·예시 파일·자기 자신은 건너뛴다
  case "$f" in
    .env.example) continue ;;
    .claude/scripts/secret-scan.sh) continue ;;
  esac

  # 바이너리 스킵
  # ⚠ head 를 중괄호로 묶어 stderr 를 버린다. grep -q 가 첫 매치에서 빠져나가면 head 가
  #   SIGPIPE 를 받는데, GNU head(리눅스·CI)는 그때 「write error: Broken pipe」를 찍는다.
  #   macOS 에서는 조용해서 로컬로는 재현되지 않는다. 게이트 출력에 「error」가 섞이면
  #   사람이 게이트 출력 전체를 흘려보게 된다
  if ! { read_file "$f" | head -c 8000; } 2>/dev/null | grep -qI . ; then
    continue
  fi

  scan_pattern "AWS Access Key" \
    "AWS 키는 코드에 두지 않습니다. 환경변수 또는 Secret Manager 로 주입하고, 노출된 키는 즉시 폐기·재발급하세요." \
    'AKIA[0-9A-Z]{16}' "$f"

  # classic PAT(ghp_) · OAuth(gho_) · user-to-server(ghu_) · server-to-server(ghs_) · refresh(ghr_)
  # ⚠ 원래 ghp_·gho_ 만 각각 {36} 으로 봤다. 그래서 ghu_·ghs_·ghr_ 형태 토큰을 커밋해도
  #   통과했고, TokenRedactor 는 이미 다섯을 전부 {20,} 로 가리고 있었다 — 어긋남이
  #   양방향이었다는 뜻이다(#28). 런타임 쪽에 맞춰 합친다.
  #   SecretPatternDriftTest 가 이 줄과 TokenRedactor 가 다시 갈라지는 것을 막는다.
  scan_pattern "GitHub Token" \
    "GITHUB_TOKEN 환경변수로 주입하세요. 이미 커밋에 남았다면 토큰을 즉시 revoke 하세요." \
    'gh[pousr]_[A-Za-z0-9]{20,}' "$f"

  scan_pattern "GitHub Fine-grained PAT" \
    "GITHUB_TOKEN 환경변수로 주입하세요. 이미 노출됐다면 즉시 revoke 하세요." \
    'github_pat_[A-Za-z0-9_]{20,}' "$f"

  scan_pattern "Slack Token" \
    "Slack 토큰은 환경변수·Secret Manager 로만 다룹니다. 노출된 토큰은 재발급하세요." \
    'xox[baprs]-[A-Za-z0-9-]{10,}' "$f"

  scan_pattern "Anthropic API Key" \
    "ANTHROPIC_API_KEY 환경변수로 주입하세요. 예시 파일에는 <REPLACE_WITH_SECRET_MANAGER> 를 씁니다." \
    'sk-ant-[A-Za-z0-9_-]{20,}' "$f"

  # 개인키는 별도 처리 (하이픈으로 시작하는 정규식이 grep 인자로 오해되는 것을 피한다)
  # ⚠ PRIVATE KEY 뒤에 곧바로 대시를 요구하면 PGP 가 통째로 빠져나간다 —
  #   -----BEGIN PGP PRIVATE KEY BLOCK----- 은 사이에 「 BLOCK」이 낀다.
  #   대시와 BEGIN 사이 공백도 마찬가지다(RFC4716/SSH2: ---- BEGIN SSH2 … ----).
  pk_hits=$(read_file "$f" | grep -nE '^-+ ?BEGIN [A-Z0-9 ]*PRIVATE KEY( BLOCK)?' || true)
  if [ -n "$pk_hits" ]; then
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      lineno="${line%%:*}"
      violations="${violations}\n  ❌ Private Key — ${f}:${lineno}\n     → 개인키는 저장소에 두지 않습니다. *.pem·*.key 는 .gitignore 대상이고, 값은 Secret Manager 로 주입합니다."
      found=$((found+1))
    done <<< "$pk_hits"
  fi
done

if [ "$found" -gt 0 ]; then
  echo "❌ 시크릿 노출 의심 ${found}건 — 커밋을 중단합니다."
  printf "%b\n" "$violations"
  echo ""
  echo "조치 방법:"
  echo "  1. 실제 값을 <REPLACE_WITH_SECRET_MANAGER> 플레이스홀더로 바꾸고 .env.example 에만 남깁니다."
  echo "  2. 실행 시 값은 환경변수 또는 Secret Manager 에서 주입합니다."
  echo "  3. 이미 유출된 크리덴셜은 파일을 지우는 것으로 끝나지 않습니다 — 즉시 폐기·재발급하세요."
  exit 1
fi

echo "✅ 시크릿 검사 통과 (SCAN_MODE=${SCAN_MODE} · 파일 $(echo "$files" | wc -l | tr -d ' ')개)"
exit 0
