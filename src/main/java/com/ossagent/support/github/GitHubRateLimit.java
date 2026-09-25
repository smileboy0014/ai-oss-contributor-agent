package com.ossagent.support.github;

import java.time.Instant;

/**
 * 응답에 실려 온 레이트리밋 상태 — {@code X-RateLimit-Limit} · {@code -Remaining} · {@code -Reset}.
 *
 * <p>이 값은 <b>{@code support} 밖으로 나가지 않는다.</b> 레이트리밋은 GitHub 의 개념이고,
 * domain 이 알면 기술이 안쪽으로 샌다 — {@code .claude/rules/conventions/architecture.md} 규율 ①.
 * 임계 대응(지연)은 어댑터와 UseCase 가 한다.
 *
 * @param limit     시간당 할당량. 헤더가 없으면 {@link #UNKNOWN_VALUE}
 * @param remaining 남은 호출 수. 헤더가 없으면 {@link #UNKNOWN_VALUE}
 * @param resetAt   할당량이 리셋되는 시각. 헤더가 없으면 {@code null}
 */
public record GitHubRateLimit(int limit, int remaining, Instant resetAt) {

    public static final int UNKNOWN_VALUE = -1;

    /** 헤더가 없거나 파싱할 수 없을 때. 「리밋이 넉넉하다」가 아니라 「모른다」다. */
    public static final GitHubRateLimit UNKNOWN =
            new GitHubRateLimit(UNKNOWN_VALUE, UNKNOWN_VALUE, null);

    public boolean isKnown() {
        return remaining != UNKNOWN_VALUE;
    }

    /** 할당량이 완전히 소진됐는가 — 1차 레이트리밋 판정의 핵심 신호다. */
    public boolean isExhausted() {
        return isKnown() && remaining == 0;
    }

    /**
     * 임계 미만인가. <b>모르면 {@code false}</b> 다.
     *
     * <p>모르는 것을 「임박」으로 취급하면 헤더를 주지 않는 응답마다 지연이 걸린다.
     */
    public boolean isBelow(int threshold) {
        return isKnown() && remaining < threshold;
    }
}
