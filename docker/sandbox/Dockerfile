# 샌드박스 이미지 — 대상 저장소의 빌드·테스트가 도는 컨테이너 (S-3 · #97)
#
# 🔴 stock eclipse-temurin 에는 git 이 없다. 검증 파이프라인의 DIFF 단계(SandboxChangeVerifier)가
#    컨테이너 안에서 `git diff` 를 돌리므로, git 이 없으면 컨테이너 기동 실패 →
#    SandboxTransientException 으로 모든 후보가 죽고 메시지는 「Docker 데몬을 확인한다」로 오진한다.
#
# ⚠ 앱 이미지(루트 Dockerfile)와 다른 것이다. 이쪽은 신뢰할 수 없는 코드가 도는 곳이라
#   우리 애플리케이션·시크릿이 들어가지 않는다.
#
# 빌드: docker/sandbox/build.sh   (oss-agent-sandbox:8 · 11 · 17 · 21 · 24 · 25)
#       SandboxImages 의 표가 이 태그를 가리킨다 — 표를 늘리면 여기 목록도 늘린다.
# 🔴 앱은 이미지를 pull 하지 않는다. 운영이 미리 빌드해 둔다.
ARG JAVA_VERSION=21
FROM eclipse-temurin:${JAVA_VERSION}-jdk

# safe.directory — 워크스페이스는 호스트 사용자 소유인데 컨테이너는 root 로 돈다.
# Linux 에서 git 이 「dubious ownership」(exit 128)으로 거부하는 것을 막는다.
# macOS Docker Desktop 은 uid 를 재매핑해 재현되지 않으므로 여기서 고정해야 배포에서 안 터진다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git \
 && rm -rf /var/lib/apt/lists/* \
 && git config --system safe.directory /workspace
