package com.ossagent.agent.domain;

/**
 * 재전송할 가치가 있는 실패 — 타임아웃 · 레이트리밋 · 5xx.
 *
 * <p>어댑터의 전송 재시도 루프가 잡는 유일한 타입이다. 상한은 {@code agent.llm.max-retries} 이고,
 * <b>파이프라인 상한({@code agent.execution.max-attempts})을 태우지 않는다</b> — S-6 · Q-6.
 */
public class LlmTransientException extends LlmException {

    /** 서버가 알려준 재시도 대기 — {@code Retry-After}. 모르면 {@code null} (#104) */
    private final transient java.time.Duration retryAfter;

    public LlmTransientException(LlmFailureReason reason, LlmCallSite callSite) {
        this(reason, callSite, null);
    }

    /**
     * @param retryAfter 🔴 429 의 {@code Retry-After}. 무시하고 0.5초 뒤 재전송하면 세 번이 1.5초 안에
     *                   끝나고 배치 전체가 종단으로 떨어진다 — GitHub 쪽은 지키는데 여기만 안 지켰다
     */
    public LlmTransientException(LlmFailureReason reason, LlmCallSite callSite,
            java.time.Duration retryAfter) {
        super(reason, callSite, null);
        if (!reason.isRetryable()) {
            throw new IllegalArgumentException(
                    "재시도 불가 사유로 transient 예외를 만들 수 없다: " + reason);
        }
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? null : retryAfter;
    }

    public java.util.Optional<java.time.Duration> retryAfter() {
        return java.util.Optional.ofNullable(retryAfter);
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
