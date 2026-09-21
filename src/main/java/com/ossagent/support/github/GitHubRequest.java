package com.ossagent.support.github;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub <b>읽기</b> 요청.
 *
 * <p>🔴 HTTP 메서드 필드가 <b>없다.</b> 이 타입으로 표현할 수 있는 것은 GET 뿐이고,
 * 그래서 {@link GitHubApiClient} 로는 원본 저장소에 아무것도 쓸 수 없다 —
 * {@code .claude/rules/context/safety-boundaries.md} S-1 을 「막는 검사」가 아니라
 * 「없는 기능」으로 지킨다. 쓰기가 필요해지는 시점(이슈 #22 Fork push · #23 Draft PR)에
 * 별도 타입을 만들고, 거기에 Fork owner 어설션을 붙인다.
 *
 * <p>🔴 <b>토큰은 여기 들어가지 않는다.</b> 인증은 헤더로만 실린다. 쿼리에 토큰이 섞이면
 * 예외 메시지와 로그의 URL 을 타고 그대로 유출된다 — S-4.
 *
 * @param path         {@code /repos/{owner}/{name}} 처럼 {@code /} 로 시작하는 경로
 * @param query        쿼리 파라미터. 순서를 보존한다(테스트에서 URL 을 고정할 수 있어야 한다)
 * @param ifNoneMatch  조건부 요청에 쓸 ETag. 없으면 {@code null}
 */
public record GitHubRequest(String path, Map<String, String> query, String ifNoneMatch) {

    public GitHubRequest {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("GitHub 요청 경로가 비어 있습니다");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("GitHub 요청 경로는 '/' 로 시작해야 합니다: " + path);
        }
        // Map.copyOf 는 순회 순서를 보장하지 않는다. 쿼리 순서가 흔들리면
        // 테스트가 URL 문자열을 고정할 수 없어 간헐 실패가 된다
        query = query == null || query.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(query));
    }

    public static GitHubRequest of(String path) {
        return new GitHubRequest(path, Map.of(), null);
    }

    /** 쿼리 파라미터를 더한 새 요청. 값이 {@code null} 이면 무시한다(선택 파라미터를 분기 없이 쓰기 위해). */
    public GitHubRequest withQuery(String name, String value) {
        if (value == null) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(query);
        merged.put(name, value);
        return new GitHubRequest(path, merged, ifNoneMatch);
    }

    /** ETag 조건부 요청으로 바꾼 새 요청. {@code null} 이면 무조건 요청이 된다. */
    public GitHubRequest withIfNoneMatch(String etag) {
        return new GitHubRequest(path, query, etag == null || etag.isBlank() ? null : etag);
    }

    public boolean isConditional() {
        return ifNoneMatch != null;
    }
}
