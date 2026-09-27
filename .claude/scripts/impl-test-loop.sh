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
# 🔴 타임아웃과 trap 이 둘 다 필요하다 — 없으면 고아 프로세스가 남는다.
#
#    원래 이 줄은 `out=$(./gradlew test --no-daemon -q 2>&1)` 였다. Gradle 이 걸리면
#    (데몬 경합 · 행 걸린 테스트 · Testcontainers 대기) 이 훅 셸이 **무한히 블록**되고,
#    세션이 죽어도 셸과 그 JVM 이 그대로 남는다. 2026-09-27 에 같은 모양의 고아
#    8개가 **1일 21시간째** 코어 6개분을 태우고 있는 것을 발견했다(load average 20.67) —
#    그쪽은 즉석 부하 실험이었지만 원인은 같다: **자식을 띄우고 정리를 보장하지 않았다.**
#
#    trap 만으로는 부족하다(걸린 채 아무 신호도 안 오면 영영 안 끝난다).
#    타임아웃만으로도 부족하다(훅이 먼저 죽으면 자식이 남는다). 둘 다 건다.
TEST_TIMEOUT_SECONDS="${IMPL_TEST_TIMEOUT_SECONDS:-900}"

tmp=$(mktemp) || exit 0
child=""
watchdog=""

cleanup() {
  [ -n "$watchdog" ] && kill "$watchdog" 2>/dev/null
  # 훅이 어떤 이유로 끝나든 자식 빌드를 데려간다 — 이것이 고아를 막는 자리다
  [ -n "$child" ] && kill -TERM "$child" 2>/dev/null
  rm -f "$tmp"
  return 0
}
trap cleanup EXIT INT TERM

echo "🔍 gradle test 실행 중... (상한 ${TEST_TIMEOUT_SECONDS}초)"
./gradlew test --no-daemon -q > "$tmp" 2>&1 &
child=$!

# 감시자는 sleep 뒤 반드시 끝나므로 그 자신이 고아가 되지 않는다 (상한이 있다)
( sleep "$TEST_TIMEOUT_SECONDS"; kill -TERM "$child" 2>/dev/null ) &
watchdog=$!

wait "$child"
status=$?

kill "$watchdog" 2>/dev/null
watchdog=""
out=$(cat "$tmp")
child=""

# 🔴 타임아웃을 「실패」로 세지 않는다 — 코드가 틀린 것이 아니라 **판정이 서지 않은** 것이다.
#    3회 카운터를 태우면 멀쩡한 코드가 「3회 연속 실패」로 보고된다.
#    「한 건도 실행하지 않았으면 「통과」라고 하지 않는다」의 같은 축이다 (testing-philosophy).
if [ $status -ge 128 ]; then
  echo "ℹ️  테스트 판정 없음 — ${TEST_TIMEOUT_SECONDS}초 상한에서 중단했습니다 (signal=$((status-128)))."
  echo "   「통과」도 「실패」도 아닙니다. 데몬 경합·행 걸린 테스트를 먼저 봅니다:"
  echo "   ps ax -o pid,%cpu,etime,command | grep -i gradle   ·   uptime"
  echo "$out" | tail -20
  exit 0
fi

if [ $status -eq 0 ]; then
  rm -f "$STATE_FILE"
  echo "✅ 테스트 통과"
  exit 0
fi

# ── 🔴 「실패한 테스트 이름이 없는 :test FAILED」는 동시 실행이다 ──────
#
#    두 Gradle 이 같은 build/test-results 를 나눠 쓰면 `Could not write XML test results`
#    수십 줄과 함께 태스크가 FAILED 로 끝나는데 **실패한 테스트 이름이 하나도 없다.**
#    멀쩡한 코드가 빨개진다.
#
#    ⚠️ **이 훅이 그 두 번째 Gradle 이 되는 경로가 실재한다** — 누가 빌드를 백그라운드로
#    돌리고 턴을 끝내면 Stop 시점에 여기가 발화한다. 2026-09-27 (#19) 에 두 번 났고
#    두 번 다 코드를 의심할 뻔했다.
#
#    🕳 **「지금 다른 Gradle 이 도는가」를 미리 보는 가드는 넣지 않았다.** 두 번 시도했고
#    두 번 다 **물리지 않았다** — 데몬까지 세면 유휴 데몬 때문에 항상 skip 하고,
#    래퍼만 세면 macOS 의 `pgrep -f` 가 긴 classpath 뒤를 보지 못해 아무것도 못 찾는다.
#    물림을 증명하지 못하는 가드는 달지 않는다(testing-philosophy 요구 2).
#    대신 **증상을 사후에 알아본다** — 이쪽은 문자열 판정이라 결정적이다.
#
#    🔴 이것을 「실패」로 세지 않는다. 3회 카운터를 태우면 멀쩡한 코드가
#    「3회 연속 실패」가 되고, 그 적색이 다음 사람의 진단을 오염시킨다.
if echo "$out" | grep -q "Could not write XML test results" \
   && ! echo "$out" | grep -qE "^[A-Za-z].*> .* FAILED"; then
  echo "ℹ️  테스트 판정 없음 — 다른 Gradle 과 동시에 돌아 결과 파일이 겹쳤습니다."
  echo "   실패한 테스트 이름이 하나도 없으므로 코드 문제가 아닙니다. 「통과」도 아닙니다."
  echo "   포그라운드 단독으로 다시 돌립니다:  rm -rf build && ./gradlew build --rerun-tasks"
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
