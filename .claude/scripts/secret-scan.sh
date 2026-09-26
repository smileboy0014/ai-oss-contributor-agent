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

# 🔴 어느 grep 으로 돌았는지 남긴다.
#
#   #64 에서 이것 때문에 구멍을 못 볼 뻔했다 — 개발 머신의 grep 이 ugrep 이었고,
#   그것은 BOM 을 알아서 건너뛴다. 프로브가 차단되길래 「이슈가 틀렸나」 했는데
#   /usr/bin/grep(BSD)으로 돌리니 그대로 샜다. CI 의 GNU grep 도 샌다.
#
#   즉 로컬 게이트가 구멍을 가리고 있었다. 훅은 PATH 에서 찾은 grep 을 쓰므로
#   사람마다 다른 구현으로 돈다. 반대 방향도 있다 — 로컬에서만 빨개지는 오탐.
#
#   ⚠ 구현을 강제하지 않는다. macOS 기본에 GNU grep 이 없고 개발 환경을 못 정한다.
#     대신 **보이게** 한다. 「초록이었다」가 어느 구현의 초록인지 알 수 있어야
#     다른 환경의 결과와 대조할 수 있다.
GREP_IMPL=$(grep --version 2>&1 | head -1)

list_files() {
  if [ "$SCAN_MODE" = "tree" ]; then
    git ls-files
  else
    git diff --cached --name-only --diff-filter=ACMR
  fi
}

# 🔴 UTF-8 BOM(EF BB BF)을 벗긴다 — 첫 줄에만.
#
#   BOM 3바이트가 줄 시작을 차지하면 개인키 검사의 ^ 뒤 대시에 닿지 않는다.
#   .pem 은 헤더가 1행이라 BOM 하나에 파일 전체가 샌다(#64).
#   OpenSSL 은 BOM 을 만들지 않지만 PowerShell 5.1 의 Out-File 기본값이 UTF-8 BOM 이라
#   `openssl genrsa | Out-File key.pem` 경로가 존재한다.
#
#   ⚠ 정규식이 아니라 여기서 고치는 이유 — read_file 은 모든 패턴이 통과하는 단일
#   지점이다. 정규식 7개를 각각 넓히는 대신 입력을 정규화한다.
#
#   ⚠ 첫 줄에만 적용한다. 파일 중간의 EF BB BF 는 BOM 이 아니라 정상 문자(U+FEFF)다.
#   ⚠ LC_ALL=C 를 붙인다. UTF-8 로케일의 sed 는 BOM 을 「부정한 바이트열」로 보고
#     거부할 수 있고, 그러면 게이트가 조용히 빈 출력을 내보낸다.
strip_bom() {
  LC_ALL=C sed $'1s/^\xef\xbb\xbf//'
}

# 🔴 strip_bom 이 동작하는지 기동 시 한 번 확인한다.
#
#   read_file 이 `… | strip_bom` 으로 끝나므로, 그 sed 가 실패하면 파이프가 빈 출력을
#   내고 **모든 패턴이 히트 0건**이 된다 — 게이트가 「✅ 통과」를 찍으며 진짜 개인키를
#   그대로 커밋시킨다. 실측으로 재현했다.
#
#   ⚠ 이것은 이 스크립트가 고치려던 것과 **같은 종류의 실패**다. 구멍을 닫으면서
#   「조용히 꺼지는 게이트」를 새로 만들 뻔했다.
#
#   파이프 안에서 개별 실패를 잡으려 하지 않는다 — $(…) 안의 pipefail 은 grep 의
#   「매치 없음(1)」과 구분되지 않아서, 탐지기를 만들면 오히려 오탐으로 게이트가 죽는다.
#   대신 **여기서 한 번, 실제 동작으로** 확인하고 안 되면 멈춘다.
#   sed 부재·로케일 거부 같은 현실적 실패는 전부 여기서 걸린다.
#
#   ⚠ 「sed 가 돌았는가」가 아니라 「BOM 이 실제로 벗겨지는가」를 본다.
if [ "$(printf '\xef\xbb\xbfx' | strip_bom)" != "x" ]; then
  echo "❌ 게이트가 고장났습니다 — strip_bom 이 BOM 을 벗기지 못합니다."
  echo "   이대로 두면 모든 패턴이 히트 0건이 되어 시크릿이 그대로 통과합니다."
  echo "   sed 가 있는지, LC_ALL=C 가 먹는지 확인하세요."
  exit 1
fi

read_file() {
  if [ "$SCAN_MODE" = "tree" ]; then
    cat "$1" 2>/dev/null
  else
    git show ":$1" 2>/dev/null
  fi | strip_bom
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

  # GCP 서비스계정 키 JSON — 공개 저장소에 가장 흔히 커밋되는 키 「파일」 포맷이다.
  # 헤더가 "private_key": " 뒤에 오므로 아래 줄 단위 검사가 놓친다(대시 앞이 따옴표다).
  #
  # ⚠ 이 패턴은 scan_pattern 을 쓰므로 <REPLACE_WITH_SECRET_MANAGER> 면제를 상속한다.
  #   아래 PEM 검사에서 그 면제를 뺀 이유(줄 하나 = 시크릿의 머리)가 여기는 해당하지
  #   않는다 — GCP SA JSON 은 키 값이 한 줄에 다 들어 있어 「줄 하나 = 시크릿 전체」다.
  #   🕳 단 그것은 **포맷팅에 기댄 전제**다. pretty-printer 가 값을 줄바꿈하면 깨지고,
  #   그때는 이 패턴 자체가 헤더 줄만 보게 된다. 실측으로 그 변종은 지금도 놓친다.
  #
  # 덤 — 한 줄 임베드({"private_key":"-----BEGIN…"})도 같이 잡힌다. 아래 줄 단위
  # 검사가 「의도적으로 안 잡는다」고 적은 그 형태인데, 여기서 넓게 잡히는 것은
  # 더 막는 방향이라 그대로 둔다.
  scan_pattern "Private Key (JSON)" \
    "서비스계정 키 파일은 저장소에 두지 않습니다. Secret Manager 또는 Workload Identity 를 쓰세요." \
    '"private_key"[[:space:]]*:[[:space:]]*"-+ ?BEGIN' "$f"

  # 개인키는 별도 처리 (하이픈으로 시작하는 정규식이 grep 인자로 오해되는 것을 피한다)
  # ⚠ PRIVATE KEY 뒤에 곧바로 대시를 요구하면 PGP 가 통째로 빠져나간다 —
  #   -----BEGIN PGP PRIVATE KEY BLOCK----- 은 사이에 「 BLOCK」이 낀다.
  #   대시와 BEGIN 사이 공백도 마찬가지다(RFC4716/SSH2: ---- BEGIN SSH2 … ----).
  # 🔴 선행 공백을 허용한다. ^ 바로 뒤에 대시를 요구했더니 들여쓴 개인키가 통째로
  #   빠져나갔다 — k8s Secret·Helm·Actions 가 전부 그 모양이고, 키가 저장소에
  #   실제로 나타나는 1순위 형태다(#58).
  #
  #   ⚠ 그렇다고 앵커를 아예 빼면 안 된다. 추적 파일 전체에서 25건이 걸리는데
  #   그중 21건이 TokenRedactorTest 다 — 런타임 스크럽이 PEM 을 지우는지 검증하려면
  #   그 테스트가 PEM 헤더를 문자열로 들고 있어야만 한다. 「정리하면 된다」가 아니라
  #   지우면 S-4 런타임 쪽 커버리지가 사라진다. 다른 게이트를 끄는 것과 같다.
  #
  #   가르는 선 — 키 파일은 헤더가 줄의 처음(들여쓰기·목록 기호 제외)에 온다.
  #   소스에서 헤더가 등장할 때는 앞에 "·*·String x = 가 붙는다.
  #
  #   🕳 실측으로 확인한 한계 — 아래는 이 검사가 놓친다.
  #     · 소스 리터럴에 박은 키 (key = "-----BEGIN…")  ← 허용이 의도다(위 21건)
  #     · .properties 인라인 (KEY="-----BEGIN…")
  #     · UTF-8 BOM 이 붙은 파일  ← 선재 구멍. BOM 이 줄 시작을 차지한다. #64
  #     · JSON 임베드는 위 "private_key" 패턴이 잡는다 — 그쪽 주석 참조
  #   여기서 닫는 것은 「키 파일을 커밋하지 마라」이지 「키 문자열이 소스에 없게 하라」가
  #   아니다. 후자는 런타임 쪽 TokenRedactor 가 맡는다.
  #   🔴 여기에는 <REPLACE_WITH_SECRET_MANAGER> 화이트리스트를 걸지 않는다.
  #   한 번 걸었다가 뺐다 — 「다른 패턴엔 다 있는 탈출구」라는 논거가 성립하지 않는다.
  #
  #     토큰 패턴: 줄 하나 = 시크릿 전체 → 면제하면 그 시크릿 하나
  #     PEM:       줄 하나 = 시크릿의 머리 → 면제하면 그 아래 본문 전체
  #
  #   즉 헤더 줄에 마커 한 번만 붙이면 진짜 키 파일이 통째로 통과한다. 게다가 이
  #   스크립트가 차단 시 「실제 값을 <REPLACE_WITH_SECRET_MANAGER> 로 바꾸라」고
  #   안내하므로, 막힌 사람이 본문을 지우는 대신 헤더에 마커를 붙이고 초록을 볼 수 있다.
  #   게이트가 제 우회법을 안내하는 모양이 된다.
  #
  #   문서에 헤더 예시를 쓸 일이 있으면 줄 시작에서 떼어 놓는다.
  #   ⚠ 인용 기호(>)·목록(-·*)은 쓰지 마라 — 바로 아래에서 그것들까지 잡는다.
  #     ✅ 쓸 수 있는 것: │ (PLAN-58.md 가 쓴다) · {@code …} · 코드 펜스 안의 다른 접두
  #   🕳 그래서 javadoc 이어짐 줄(「 * -----BEGIN …」)은 탈출구 없이 막힌다.
  #     지금 저장소에 그런 줄은 없지만, 생기면 예시 쪽을 고쳐라 —
  #     화이트리스트를 되살리는 것은 위에 적은 이유로 답이 아니다.
  pk_hits=$(read_file "$f" \
    | grep -nE '^[[:space:]]*([-*>][[:space:]]+)?-+ ?BEGIN [A-Z0-9 ]*PRIVATE KEY( BLOCK)?' \
    || true)
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
  echo ""
  echo "  (grep: ${GREP_IMPL})"
  exit 1
fi

echo "✅ 시크릿 검사 통과 (SCAN_MODE=${SCAN_MODE} · 파일 $(echo "$files" | wc -l | tr -d ' ')개)"
echo "   grep: ${GREP_IMPL}"
exit 0
