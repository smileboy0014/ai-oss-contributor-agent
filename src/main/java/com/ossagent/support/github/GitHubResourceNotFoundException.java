package com.ossagent.support.github;

/**
 * 404 — 대상이 없다.
 *
 * <p>「{@code CONTRIBUTING.md} 가 없는 저장소」처럼 <b>정상 상황</b>인 경우가 많다.
 * 그래서 호출자(어댑터)가 이 예외를 {@code Optional.empty()} 로 번역하는 것이 기본이고,
 * 예외를 그대로 위로 던지는 것은 「없으면 안 되는 것」을 조회할 때뿐이다.
 */
public class GitHubResourceNotFoundException extends GitHubApiException {

    public GitHubResourceNotFoundException(String message) {
        super(404, message);
    }
}
