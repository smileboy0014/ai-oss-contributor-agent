package com.ossagent.support.github;

/**
 * 403 중 <b>진짜 권한 부족</b>.
 *
 * <p>레이트리밋 신호(`X-RateLimit-Remaining: 0` · `Retry-After` · 본문의 secondary 문구)가
 * 하나도 없을 때만 이 타입이 된다. 재시도 대상이 아니다 — 같은 토큰으로 몇 번을 더 불러도 같다.
 *
 * <p>판정이 애매하면 이쪽으로 떨어뜨린다. 오판의 결과가 「무한 재시도」가 아니라
 * 「빠른 실패」가 되도록 하는 것이 의도다 — {@link GitHubErrorTranslator}.
 */
public class GitHubPermissionException extends GitHubApiException {

    public GitHubPermissionException(int status, String message) {
        super(status, message);
    }
}
