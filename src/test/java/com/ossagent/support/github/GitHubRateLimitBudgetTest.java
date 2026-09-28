package com.ossagent.support.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 임계가 <b>버킷 크기</b>에 맞춰 깎이는지 — #103.
 *
 * <p>{@code rate-limit-threshold: 100} 은 인증 5,000/h 을 전제로 한 절대값이다. 미인증(60/h)에
 * 그대로 대면 첫 응답 직후 remaining(59) 이 임계(100) 미만이라 리셋까지 모든 호출을 거부하고,
 * 막는 동안엔 응답이 없어 갱신도 없다 — 방어가 스스로를 잠그는 구조였다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitHubRateLimitBudgetTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int THRESHOLD = 100;

    @Test
    @DisplayName("🔴 미인증 60/h 버킷에서 첫 응답(remaining 59)은 임계 미만이 아니다")
    void 미인증_버킷은_첫_응답으로_잠기지_않는다() {
        GitHubRateLimitBudget budget = new GitHubRateLimitBudget(CLOCK);
        budget.record("/repos/a/b", new GitHubRateLimit(60, 59, NOW.plus(Duration.ofHours(1))), THRESHOLD);

        assertThatCode(() -> budget.refuseIfBudgetLow("/repos/a/b", THRESHOLD))
                .as("60 짜리 버킷에 100 을 대면 첫 응답부터 잠긴다 — 임계는 버킷의 10% 로 깎여야 한다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("미인증 버킷도 진짜 바닥(remaining 5)에서는 막는다")
    void 미인증_버킷도_바닥에서는_막는다() {
        GitHubRateLimitBudget budget = new GitHubRateLimitBudget(CLOCK);
        budget.record("/repos/a/b", new GitHubRateLimit(60, 5, NOW.plus(Duration.ofHours(1))), THRESHOLD);

        assertThatThrownBy(() -> budget.refuseIfBudgetLow("/repos/a/b", THRESHOLD))
                .isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    @DisplayName("인증 5,000/h 버킷에서는 임계 100 이 그대로다")
    void 인증_버킷은_임계가_그대로다() {
        GitHubRateLimitBudget budget = new GitHubRateLimitBudget(CLOCK);
        budget.record("/repos/a/b", new GitHubRateLimit(5000, 99, NOW.plus(Duration.ofHours(1))), THRESHOLD);

        assertThatThrownBy(() -> budget.refuseIfBudgetLow("/repos/a/b", THRESHOLD))
                .isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    @DisplayName("버킷 크기를 모르면 절대값 그대로다 — 모르는 것을 넉넉하다로 읽지 않는다")
    void 버킷을_모르면_절대값이다() {
        assertThat(GitHubRateLimit.effectiveThreshold(THRESHOLD, GitHubRateLimit.UNKNOWN_VALUE))
                .isEqualTo(THRESHOLD);
        assertThat(GitHubRateLimit.effectiveThreshold(THRESHOLD, 30)).isEqualTo(3);
        assertThat(GitHubRateLimit.effectiveThreshold(THRESHOLD, 5)).as("최소 1").isEqualTo(1);
    }
}
