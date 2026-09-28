package com.ossagent.agent.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmTransientException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 백오프가 서버의 {@code Retry-After} 를 지키는지 — #104. GitHub 쪽은 지키는데 LLM 만 안 지켰다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LlmRetryPolicyTest {

    private final LlmRetryPolicy policy = new LlmRetryPolicy(2, Duration.ofMillis(500));

    @Test
    @DisplayName("Retry-After 가 우리 백오프보다 길면 그것을 지킨다")
    void retry_after_를_지킨다() {
        var failure = new LlmTransientException(LlmFailureReason.RATE_LIMITED, LlmCallSite.ANALYZE,
                Duration.ofSeconds(30));

        assertThat(policy.backoffFor(failure, 0)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("Retry-After 가 없으면 우리 백오프다 — 0.5초 · 1초")
    void 힌트가_없으면_우리_백오프다() {
        var failure = new LlmTransientException(LlmFailureReason.UNAVAILABLE, LlmCallSite.ANALYZE);

        assertThat(policy.backoffFor(failure, 0)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.backoffFor(failure, 1)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("터무니없는 Retry-After 는 상한에서 자른다 — 그 뒤는 미룸(#98)의 몫이다")
    void 상한을_넘는_힌트는_자른다() {
        var failure = new LlmTransientException(LlmFailureReason.RATE_LIMITED, LlmCallSite.ANALYZE,
                Duration.ofHours(3));

        assertThat(policy.backoffFor(failure, 0)).isEqualTo(LlmRetryPolicy.MAX_RETRY_AFTER);
    }
}
