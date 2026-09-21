#!/bin/bash
# Stop hook: 최근 변경이 있을 때만 ./gradlew test 실행
# 대상 판별 = 미커밋 변경 ∪ 최근 30분 내 수정된 src/**/*.java (합집합)
# 테스트 실패가 3회 연속되면 수동 개입을 요구한다.

set -uo pipefail

STATE_FILE="${HOME}/.claude/impl-retry-count-oss-agent"
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$ROOT" || exit 0

# ── 변경 대상 판별 ──────────────────────────────────────────────
# ①만으로는 커밋 직후를 놓치고, ②만으로는 오래 걸린 턴을 놓친다. 합집합으로 본다.
changed=$(
  {
    git diff --name-only HEAD 2>/dev/null
    git ls-files --others --exclude-standard 2>/dev/null
    find src -type f -name '*.java' -mmin -30 2>/dev/null
  } | sort -u
)

affected=$(echo "$changed" | grep -E '^src/.*\.java$' || true)

if [ -z "$affected" ]; then
  rm -f "$STATE_FILE"
  exit 0                      # 대상 없음 — 조용히 종료
fi

# ── 실행 가능 여부 ──────────────────────────────────────────────
if [ ! -x "./gradlew" ]; then
  rm -f "$STATE_FILE"
  echo "ℹ️  테스트를 실행하지 않았습니다 — ./gradlew 이 없거나 실행 권한이 없습니다."
  echo "   (이 프로젝트에는 mvn·gradle CLI 가 없어 래퍼가 유일한 경로입니다)"
  exit 0
fi

if [ ! -d "src/test" ]; then
  rm -f "$STATE_FILE"
  echo "ℹ️  테스트를 실행하지 않았습니다 — src/test 가 없습니다."
  exit 0
fi

# ── 테스트 실행 ────────────────────────────────────────────────
echo "🔍 gradle test 실행 중..."
out=$(./gradlew test --no-daemon -q 2>&1)
status=$?

if [ $status -eq 0 ]; then
  rm -f "$STATE_FILE"
  echo "✅ 테스트 통과"
  exit 0
fi

count=$(cat "$STATE_FILE" 2>/dev/null || echo 0)
count=$((count+1))
echo "$count" > "$STATE_FILE"

if [ "$count" -ge 3 ]; then
  rm -f "$STATE_FILE"
  echo "❌ 테스트가 3회 연속 실패했습니다. 수동 개입이 필요합니다."
  echo "$out" | tail -40
  echo ""
  echo "다음 중 선택해주세요:"
  echo "1. 수동으로 수정 후 재시도"
  echo "2. 테스트 스킵하고 진행"
  echo "3. 구현 중단"
  exit 0
fi

echo "❌ 테스트 실패 (${count}/3회)"
echo "$out" | tail -30
exit 0
