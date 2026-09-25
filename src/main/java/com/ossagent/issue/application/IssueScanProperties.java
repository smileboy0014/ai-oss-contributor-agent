package com.ossagent.issue.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이슈 스캔 설정 — #8.
 *
 * <p>⚠ <b>레이트리밋 임계는 여기 없다.</b> 이미 {@code github.rate-limit-threshold} 가 있고,
 * 판정은 {@code GitHubApiClient} 가 한다(§3.4). 같은 값을 두 곳에 두면 갈라지는 순간
 * 진실이 둘이 된다.
 *
 * @param maxPagesPerScan       스캔 1회의 페이지 상한. 상한 없이 {@code hasNext} 를 따라가면
 *                              첫 스캔이 레이트리밋을 통째로 태운다. 잘리면
 *                              {@code ScanResult.hasMore} 로 드러나고 다음 스캔이 이어받는다
 * @param secondaryLimitBackoff GitHub 이 {@code Retry-After} 도 {@code resetAt} 도 주지 않았을
 *                              때의 기본 지연. <b>보수적으로</b> 잡는다 — 짧게 잡으면 2차 리밋에서
 *                              차단이 더 길어진다.
 *                              ⚠ <b>회차별로 늘리지 않는다</b>(알려진 한계). 연속 충돌 횟수는
 *                              스캔을 넘어 살아남아야 해서 컬럼이 하나 더 필요한데, 신호가 전혀
 *                              없는 2차 리밋은 드물어 그 비용이 크다. 필요해지면 그때 추가한다
 */
@ConfigurationProperties("github.scan")
public record IssueScanProperties(Integer maxPagesPerScan, Duration secondaryLimitBackoff) {

    private static final int DEFAULT_MAX_PAGES = 10;
    private static final Duration DEFAULT_BACKOFF = Duration.ofMinutes(5);

    public IssueScanProperties {
        maxPagesPerScan = maxPagesPerScan == null ? DEFAULT_MAX_PAGES : maxPagesPerScan;
        secondaryLimitBackoff = secondaryLimitBackoff == null ? DEFAULT_BACKOFF : secondaryLimitBackoff;
        if (maxPagesPerScan < 1) {
            throw new IllegalArgumentException("페이지 상한은 1 이상이어야 합니다: " + maxPagesPerScan);
        }
        if (secondaryLimitBackoff.isNegative() || secondaryLimitBackoff.isZero()) {
            throw new IllegalArgumentException("2차 리밋 백오프는 양수여야 합니다: " + secondaryLimitBackoff);
        }
    }
}
