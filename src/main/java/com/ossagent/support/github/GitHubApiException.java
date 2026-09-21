package com.ossagent.support.github;

import com.ossagent.support.secret.TokenRedactor;

/**
 * GitHub 대외 호출 실패의 공통 타입.
 *
 * <p>하위 타입이 존재하는 이유는 하나다 — <b>403 을 구분하기 위해서</b>.
 * 403 은 권한 오류일 수도 있고 2차 레이트리밋(abuse detection)일 수도 있는데,
 * 후자를 권한 오류로 처리하면 무한 재시도에 빠지고 전자를 리밋으로 처리하면 영원히 지연된다 —
 * {@code .claude/rules/context/external-deps.md}.
 *
 * <p>🔴 메시지는 생성 시점에 {@link TokenRedactor} 를 통과한다. 예외 메시지는
 * 로그·{@code AgentRun.errorMessage}·에러 응답으로 번지는 경로라 S-4 의 주된 유출구다.
 */
public class GitHubApiException extends RuntimeException {

    /** 응답을 받지 못한 실패(연결 거부·타임아웃)를 나타내는 상태값. */
    public static final int NO_STATUS = 0;

    private final int status;

    public GitHubApiException(int status, String message) {
        super(TokenRedactor.redact(message));
        this.status = status;
    }

    public GitHubApiException(int status, String message, Throwable cause) {
        super(TokenRedactor.redact(message), cause);
        this.status = status;
    }

    /** HTTP 상태코드. 응답을 받지 못했으면 {@link #NO_STATUS}. */
    public int status() {
        return status;
    }
}
