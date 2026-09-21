package com.ossagent.support.github;

import java.util.Optional;

/**
 * 설정에 담긴 고정 classic PAT 을 그대로 쓰는 자격증명 — Q-1 확정(2026-09-21)의 구현.
 *
 * <p>⚠ fine-grained PAT 이나 GitHub App 설치 토큰으로 바꾸지 않는다. 더 안전해 보이지만
 * 우리가 멤버가 아닌 upstream 에 PR 을 만들지 못해 PR 생성이 403 으로 죽는다.
 */
public class StaticTokenCredentials implements GitHubCredentials {

    private final String authorizationHeader;

    public StaticTokenCredentials(String token) {
        this.authorizationHeader = token == null || token.isBlank() ? null : "Bearer " + token.trim();
    }

    public static StaticTokenCredentials from(GitHubProperties properties) {
        return new StaticTokenCredentials(properties.token());
    }

    @Override
    public Optional<String> authorizationHeader() {
        return Optional.ofNullable(authorizationHeader);
    }

    /** 🔴 값을 찍지 않는다. 존재 여부만 드러낸다 — S-4. */
    @Override
    public String toString() {
        return "StaticTokenCredentials[present=" + (authorizationHeader != null) + "]";
    }
}
