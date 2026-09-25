package com.ossagent.support.github;

/**
 * 5xx · 연결 실패 · 타임아웃 — <b>다시 걸면 성공할 수 있는</b> 실패.
 *
 * <p>이 프로젝트에서 재시도 대상은 이 타입 하나뿐이다({@link GitHubRetryPolicy}).
 * 레이트리밋은 재시도가 아니라 <b>지연</b>이고, 권한·404 는 재시도해도 같다.
 */
public class GitHubTransientException extends GitHubApiException {

    public GitHubTransientException(int status, String message) {
        super(status, message);
    }

    public GitHubTransientException(int status, String message, Throwable cause) {
        super(status, message, cause);
    }
}
