package com.ossagent.support.github;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpHeaders;

/**
 * GitHub 응답 헤더 파싱. 형식을 아는 곳을 한 군데로 모은다.
 *
 * <p>헤더는 <b>없을 수도, 깨져 있을 수도</b> 있다. 프록시가 지우기도 하고 에러 응답에는 빠지기도 한다.
 * 그래서 이 클래스의 모든 메서드는 파싱 실패 시 예외 대신 「모름」({@link GitHubRateLimit#UNKNOWN}
 * · {@code null})을 돌려준다 — 헤더 파싱 실패로 본 호출이 실패하면 원인이 엉뚱한 곳에서 드러난다.
 */
final class GitHubHeaders {

    private static final String RATE_LIMIT = "X-RateLimit-Limit";
    private static final String RATE_REMAINING = "X-RateLimit-Remaining";
    private static final String RATE_RESET = "X-RateLimit-Reset";
    private static final String RETRY_AFTER = "Retry-After";

    private GitHubHeaders() {
    }

    /** 레이트리밋 3종 헤더. 하나라도 없으면 그 자리만 「모름」이 된다. */
    static GitHubRateLimit rateLimit(HttpHeaders headers) {
        int limit = intHeader(headers, RATE_LIMIT);
        int remaining = intHeader(headers, RATE_REMAINING);
        Instant resetAt = epochSecondHeader(headers, RATE_RESET);
        if (limit == GitHubRateLimit.UNKNOWN_VALUE
                && remaining == GitHubRateLimit.UNKNOWN_VALUE
                && resetAt == null) {
            return GitHubRateLimit.UNKNOWN;
        }
        return new GitHubRateLimit(limit, remaining, resetAt);
    }

    /**
     * {@code Retry-After}. 2차 레이트리밋(abuse detection)의 가장 확실한 신호다.
     *
     * <p>RFC 는 「초」와 「HTTP-date」 두 형식을 허용한다. GitHub 은 보통 초를 주지만
     * 날짜로 오는 경우를 버리면 2차 리밋을 권한 오류로 오판하게 되므로 둘 다 읽는다.
     * 날짜 형식을 지금 시각과 비교해야 해서 {@link Clock} 이 필요하다.
     */
    static Duration retryAfter(HttpHeaders headers, Clock clock) {
        String raw = headers.getFirst(RETRY_AFTER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            // 초가 아니면 HTTP-date 로 해석한다
        }
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration until = Duration.between(clock.instant(), retryAt);
            return until.isNegative() ? Duration.ZERO : until;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** {@code ETag}. 조건부 요청({@code If-None-Match})으로 되돌려 보낼 값이다 — 이슈 #8. */
    static String etag(HttpHeaders headers) {
        String etag = headers.getETag();
        return etag == null || etag.isBlank() ? null : etag;
    }

    private static int intHeader(HttpHeaders headers, String name) {
        String raw = headers.getFirst(name);
        if (raw == null || raw.isBlank()) {
            return GitHubRateLimit.UNKNOWN_VALUE;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return GitHubRateLimit.UNKNOWN_VALUE;
        }
    }

    private static Instant epochSecondHeader(HttpHeaders headers, String name) {
        String raw = headers.getFirst(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(raw.trim()));
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
