#!/usr/bin/env bash
# 샌드박스 이미지를 전부 빌드한다 — SandboxImages.BY_JAVA_VERSION 과 같은 목록 (#97).
#
# 사용: docker/sandbox/build.sh            # 전부
#       docker/sandbox/build.sh 21         # 하나만
#
# 🔴 앱은 이미지를 pull 하지 않는다(외부 의존 문서 §Docker). 기동 전에 이것을 한 번 돌린다.
set -euo pipefail

cd "$(dirname "$0")"

VERSIONS=("$@")
if [ ${#VERSIONS[@]} -eq 0 ]; then
  VERSIONS=(8 11 17 21 24 25)
fi

for v in "${VERSIONS[@]}"; do
  echo "▶ oss-agent-sandbox:${v}  (FROM eclipse-temurin:${v}-jdk)"
  docker build --build-arg "JAVA_VERSION=${v}" -t "oss-agent-sandbox:${v}" .
done

echo "✅ 빌드 완료: ${VERSIONS[*]}"
