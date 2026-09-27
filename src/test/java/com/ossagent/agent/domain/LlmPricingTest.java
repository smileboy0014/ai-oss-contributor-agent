package com.ossagent.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 단가 × 토큰 — 이슈 #71.
 *
 * <p>「비용이 보이지 않으면 재시도 루프가 조용히 돈을 태운다」의 나머지 절반이다.
 * 토큰은 #25 가 이미 세고 있고, 여기는 <b>곱셈이 맞는가</b>와
 * <b>모르는 것을 0 으로 접지 않는가</b>를 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LlmPricingTest {

    private static final LlmPricing SONNET =
            new LlmPricing(new BigDecimal("3.00"), new BigDecimal("15.00"));

    @Test
    void 입력과_출력에_각각_단가를_곱한다() {
        // 1,000,000 × 3 USD/1M + 500,000 × 15 USD/1M = 3 + 7.5
        BigDecimal cost = SONNET.costOf(new LlmUsage(1_000_000, 500_000));

        assertThat(cost).isEqualByComparingTo("10.5");
    }

    @Test
    @DisplayName("🔴 호출 한 건이 0 으로 접히지 않는다 — 소수 자리가 좁으면 장부가 0 이 된다")
    void 작은_호출도_0_이_되지_않는다() {
        // 실제 호출 규모(입력 1.2만 · 출력 800 토큰)다. 소수 자리를 좁게 잡으면
        // 여기서 0 으로 반올림되고, 그러면 누적 비용이 영원히 0 으로 보인다
        BigDecimal cost = SONNET.costOf(new LlmUsage(12_000, 800));

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
        assertThat(cost).isEqualByComparingTo("0.048");
    }

    @Test
    @DisplayName("⚠ 사용량 0 은 「안 썼다」다 — 「모른다」는 호출자가 가른다")
    void 사용량이_0_이면_비용도_0_이다() {
        assertThat(SONNET.costOf(LlmUsage.unknown())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("🔴 한쪽만 적힌 단가를 거부한다 — 그 방향이 공짜인 장부가 만들어진다")
    void 단가가_한쪽만_있으면_거부한다() {
        assertThatThrownBy(() -> new LlmPricing(new BigDecimal("3.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output");
        assertThatThrownBy(() -> new LlmPricing(null, new BigDecimal("15.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input");
    }

    @Test
    void 음수_단가를_거부한다() {
        assertThatThrownBy(() -> new LlmPricing(new BigDecimal("-1"), new BigDecimal("15.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("사용량을 모르면 0 으로 접지 않고 거부한다")
    void 사용량이_null_이면_거부한다() {
        assertThatThrownBy(() -> SONNET.costOf(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
