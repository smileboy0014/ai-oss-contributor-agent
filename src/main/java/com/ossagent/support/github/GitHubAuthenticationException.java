package com.ossagent.support.github;

/**
 * 401 — 토큰이 없거나 유효하지 않다.
 *
 * <p>재시도해도 결과가 같다. 설정을 고쳐야 하는 실패다.
 */
public class GitHubAuthenticationException extends GitHubApiException {

    public GitHubAuthenticationException(String message) {
        super(401, message);
    }
}
