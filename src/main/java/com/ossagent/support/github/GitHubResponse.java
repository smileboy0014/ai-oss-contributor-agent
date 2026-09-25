package com.ossagent.support.github;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * GitHub 읽기 응답 — 파싱 전 JSON + 조건부 요청·레이트리밋 메타데이터.
 *
 * <p><b>본문을 도메인 타입으로 바꾸지 않는다.</b> 매핑은 각 도메인의 {@code adapter/out/github}
 * 가 한다 — {@code .claude/rules/conventions/architecture.md} 「LLM·GitHub 응답 파싱은
 * adapter/out 에서 끝낸다」. 여기서 도메인 타입을 알면 {@code support} 가 모든 도메인에 묶인다.
 *
 * <p>{@link #rateLimit} 이 이 타입에 실려 있는 것이 이슈 #6 완료조건 ③ 「레이트리밋 헤더 노출」의
 * 실체다. <b>다만 {@code support} 경계를 넘지 않는다</b> — 어댑터가 읽고 판단하며,
 * 도메인 값({@code IssuePage} 등)에는 싣지 않는다(규율 ①).
 *
 * @param body        응답 본문. {@code notModified} 이거나 본문이 없으면 {@code null}
 * @param etag        {@code ETag}. 다음 호출의 {@code If-None-Match} 로 되돌려 보낸다
 * @param linkHeader  {@code Link} 헤더 원문. 다음 페이지 존재 여부가 여기 있다
 * @param notModified 304 응답인가. 본문이 없고 <b>이전 값이 유효</b>하다는 뜻이다
 * @param rateLimit   이 응답 시점의 레이트리밋 상태. 항상 non-null ({@link GitHubRateLimit#UNKNOWN})
 */
public record GitHubResponse(
        JsonNode body,
        String etag,
        String linkHeader,
        boolean notModified,
        GitHubRateLimit rateLimit) {

    public GitHubResponse {
        rateLimit = rateLimit == null ? GitHubRateLimit.UNKNOWN : rateLimit;
    }

    static GitHubResponse notModified(String etag, GitHubRateLimit rateLimit) {
        return new GitHubResponse(null, etag, null, true, rateLimit);
    }

    static GitHubResponse of(JsonNode body, String etag, String linkHeader,
            GitHubRateLimit rateLimit) {
        return new GitHubResponse(body, etag, linkHeader, false, rateLimit);
    }

    /** 매핑할 본문이 있는가. 304 이거나 빈 응답이면 {@code false}. */
    public boolean hasBody() {
        return body != null && !body.isNull();
    }
}
