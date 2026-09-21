#!/bin/bash
# PreToolUse(Bash(git commit:*)) hook: 커밋 전 Gradle 검증
# 스테이징에 src/** 또는 빌드 스크립트 변경이 있을 때만 ./gradlew check 를 돌린다.
# 검증을 한 건도 실행하지 않았으면 「통과」라고 말하지 않는다.

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$ROOT" || exit 0

files=$(git diff --cached --name-only)
if [ -z "$files" ]; then
  exit 0
fi

# 검증 대상 판별 — 소스 또는 빌드 배선이 움직였을 때만
affected=$(echo "$files" | grep -E '^(src/|build\.gradle\.kts$|settings\.gradle\.kts$|gradle/)' || true)

if [ -z "$affected" ]; then
  echo "ℹ️  src/ · 빌드 스크립트 외 변경만 스테이징됨 — 검증 스킵"
  exit 0
fi

if [ ! -x "./gradlew" ]; then
  if [ -f "./gradlew" ]; then
    echo "ℹ️  ./gradlew 에 실행 권한이 없어 검증을 실행하지 않았습니다 — 커밋은 진행합니다."
    echo "   → chmod +x ./gradlew"
  else
    echo "ℹ️  ./gradlew 이 없어 검증을 실행하지 않았습니다 — 커밋은 진행합니다."
    echo "   → Gradle 래퍼를 복원한 뒤 다시 커밋하세요. (이 프로젝트에는 mvn·gradle CLI 가 없습니다)"
  fi
  exit 0
fi

echo "🔍 gradle check 실행 중..."
out=$(./gradlew check --no-daemon -q 2>&1)
status=$?

if [ $status -ne 0 ]; then
  echo "❌ gradle check 실패:"
  echo "$out" | tail -30
  echo ""
  echo "❌ 커밋 전 검증 실패. 수정 후 다시 커밋하세요."
  exit 1
fi

echo "✅ gradle check 통과"
exit 0
