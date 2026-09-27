package com.ossagent.agent.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 모델 하나의 단가 — <b>100만 토큰당 금액</b>이다. 통화는 {@code USD} 하나로 고정한다.
 *
 * <h2>🔴 단가는 코드에 박지 않는다</h2>
 *
 * <p>단가는 모델·시점·계약에 따라 다르다. 박아 넣으면 <b>틀린 숫자를 자신 있게 보여준다</b> —
 * 「비용이 보이지 않는다」보다 나쁠 수 있다. 그래서 이 값은 설정
 * ({@code agent.llm.pricing.<model>.input} / {@code .output})에서만 온다.
 *
 * <p>🔴 <b>단가가 없으면 비용을 0 으로 만들지 않는다.</b> 0 은 「공짜」로 읽히고, 그것은
 * 「모른다」와 전혀 다른 말이다. 단가가 없으면 비용 미터를 <b>만들지 않는다</b> —
 * 토큰({@code ossagent.llm.tokens})은 그대로 세므로 나중에 곱하면 된다.
 *
 * <h2>⚠️ 절반만 적은 설정을 통과시키지 않는다</h2>
 *
 * <p>{@code input} 만 적고 {@code output} 을 빠뜨리면 바인딩 결과가 {@code null} 이고,
 * 그대로 두면 <b>출력 토큰이 공짜인 장부</b>가 만들어진다. 생성자가 거부해
 * <b>기동 시점에</b> 드러나게 한다 — 운영 중에 조용히 반값이 찍히는 것보다 낫다.
 *
 * @param input  입력 100만 토큰당 USD
 * @param output 출력 100만 토큰당 USD
 */
public record LlmPricing(BigDecimal input, BigDecimal output) {

    /** 토큰 수를 100만 단위 단가에 맞추는 제수. */
    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    /**
     * 금액 소수 자리. 100만 토큰당 단가를 토큰 단위로 나누면 값이 매우 작아진다 —
     * 자리를 좁게 잡으면 호출 한 건이 <b>0 으로 접힌다.</b>
     */
    private static final int SCALE = 10;

    public LlmPricing {
        input = require(input, "input");
        output = require(output, "output");
    }

    /** 이 호출이 먹은 금액(USD). 토큰이 0 이면 0 이다 — 「모른다」는 호출자가 가른다. */
    public BigDecimal costOf(LlmUsage usage) {
        if (usage == null) {
            throw new IllegalArgumentException("usage 는 필수다 — 모르는 사용량을 0 으로 접지 않는다");
        }
        return input.multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(output.multiply(BigDecimal.valueOf(usage.outputTokens())))
                .divide(PER_MILLION, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal require(BigDecimal rate, String name) {
        if (rate == null) {
            throw new IllegalArgumentException(
                    ("agent.llm.pricing.<model>.%s 가 비어 있다 — 한쪽만 적으면 그 방향이 "
                            + "공짜인 장부가 만들어진다. 둘 다 적거나 둘 다 비운다").formatted(name));
        }
        if (rate.signum() < 0) {
            throw new IllegalArgumentException(
                    "agent.llm.pricing.<model>.%s 는 음수일 수 없다: %s".formatted(name, rate));
        }
        return rate;
    }
}
