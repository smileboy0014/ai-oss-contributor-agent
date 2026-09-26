package com.ossagent.agent.domain;

/**
 * 다시 보내도 같은 실패 — 지원하지 않는 빌드 도구, 격리 설정이 성립하지 않는 요청.
 *
 * <p>🔴 <b>안전 경계 위반 시도는 전부 여기로 온다</b>(워크스페이스가 루트 밖 · 볼륨 이름이
 * 경로 형태 · 상한 누락). 재시도로 풀릴 성질이 아니고, 풀려서도 안 된다 —
 * 「몇 번 더 해 보면 통과하는 방어」는 방어가 아니다.
 */
public class SandboxPermanentException extends SandboxException {

    public SandboxPermanentException(String message) {
        super(message);
    }

    @Override
    public boolean retryable() {
        return false;
    }
}
