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
        return isKnown() && remaining < effectiveThreshold(threshold);
    }

    /** 이 버킷에 실제로 적용되는 임계 — 로그와 판정이 같은 숫자를 쓴다. */
    public int effectiveThreshold(int threshold) {
        return effectiveThreshold(threshold, limit);
    }

    /**
     * 🔴 임계를 <b>버킷 크기에 맞춰 깎는다</b> — #103.
     *
     * <p>{@code github.rate-limit-threshold: 100} 은 인증 5,000/h 을 전제로 정한 절대값이다.
     * 미인증(60/h)·Search(30/min) 버킷에 그대로 대면 <b>첫 응답 직후부터</b> remaining 이 임계
     * 미만이라 모든 호출을 거부하고, 막는 동안엔 응답이 없어 갱신도 없다 — 리셋까지 1시간
     * 자물쇠다. yml 의 「비어 있으면 미인증으로 동작한다」가 거짓이 되던 자리다.
     *
     * <p>버킷의 10% 를 상한으로 둔다: 5,000 → 100 그대로, 60 → 6, 30 → 3. 최소 1.
     * {@code limit} 을 모르면 절대값 그대로다 — 모르는 것을 「넉넉하다」로 읽지 않는다.
     */
    public static int effectiveThreshold(int threshold, int limit) {
        if (limit <= 0 || limit == UNKNOWN_VALUE) {
            return threshold;
        }
        return Math.max(1, Math.min(threshold, limit / 10));
    }
}
