package com.ossagent.support.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 무엇을 다시 걸고 무엇을 걸지 않는가.
 *
 * <p>「전부 재시도」는 레이트리밋을 태우고, 「전부 실패」는 일시적 장애에 파이프라인을 끊는다.
 */
class GitHubRetryPolicyTest {

    /** 고정값을 쓴다 — 시각에 의존하는 판정이 없음을 드러내기 위해서다. */
    private static final Instant RESET_AT = Instant.parse("2026-09-22T10:00:00Z");

    private final GitHubRetryPolicy policy = new GitHubRetryPolicy(2, Duration.ofMillis(500));

    @Test
    @DisplayName("일시적 실패만 재시도한다")
    void 일시적_실패만_재시도한다() {
        assertThat(policy.shouldRetry(new GitHubTransientException(500, "서버 오류"), 0)).isTrue();
    }

    @Test
    @DisplayName("레이트리밋은 재시도하지 않는다 — 재시도가 아니라 지연이다")
    void 레이트리밋은_재시도하지_않는다() {
        GitHubRateLimitException limit = new GitHubRateLimitException(403,
                GitHubRateLimitException.Scope.PRIMARY, RESET_AT, null, "소진");

        assertThat(policy.shouldRetry(limit, 0))
                .as("즉시 다시 걸면 남은 예산만 더 태우고 2차 리밋에서는 차단이 길어진다")
                .isFalse();
    }

    @Test
    @DisplayName("권한·인증·404 는 재시도해도 결과가 같다")
    void 고쳐지지_않는_실패는_재시도하지_않는다() {
        assertThat(policy.shouldRetry(new GitHubPermissionException(403, "권한 없음"), 0)).isFalse();
        assertThat(policy.shouldRetry(new GitHubAuthenticationException("토큰 없음"), 0)).isFalse();
        assertThat(policy.shouldRetry(new GitHubResourceNotFoundException("없음"), 0)).isFalse();
    }

    @Test
    @DisplayName("상한을 넘으면 더 이상 재시도하지 않는다")
    void 상한을_소진하면_멈춘다() {
        GitHubTransientException transient1 = new GitHubTransientException(503, "일시 장애");

        assertThat(policy.shouldRetry(transient1, 0)).isTrue();
        assertThat(policy.shouldRetry(transient1, 1)).isTrue();
        assertThat(policy.shouldRetry(transient1, 2))
                .as("상한이 없으면 5xx 폭주 시 호출량이 배로 늘어난다")
                .isFalse();
    }

    @Test
    @DisplayName("백오프는 시도마다 선형으로 늘어난다")
    void 백오프는_선형으로_증가한다() {
        assertThat(policy.backoffFor(0)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    @DisplayName("재시도를 0 으로 끌 수 있다")
    void 재시도를_끌_수_있다() {
        GitHubRetryPolicy noRetry = new GitHubRetryPolicy(0, Duration.ZERO);

        assertThat(noRetry.shouldRetry(new GitHubTransientException(500, "서버 오류"), 0)).isFalse();
    }

    @Test
    void 잘못된_설정을_거부한다() {
        assertThatThrownBy(() -> new GitHubRetryPolicy(-1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitHubRetryPolicy(1, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("설정에서 만든 정책이 같은 상한을 쓴다")
    void 설정에서_정책을_만든다() {
        GitHubProperties properties = new GitHubProperties(null, null, null, null, 3,
                Duration.ofMillis(200), 100);

        GitHubRetryPolicy fromProperties = GitHubRetryPolicy.from(properties);

        assertThat(fromProperties.maxRetries()).isEqualTo(3);
        assertThat(fromProperties.backoffFor(0)).isEqualTo(Duration.ofMillis(200));
    }
}
