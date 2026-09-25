package com.ossagent.agent.domain;

/**
 * 재전송할 가치가 있는 실패 — 타임아웃 · 레이트리밋 · 5xx.
 *
 * <p>어댑터의 전송 재시도 루프가 잡는 유일한 타입이다. 상한은 {@code agent.llm.max-retries} 이고,
 * <b>파이프라인 상한({@code agent.execution.max-retries})을 태우지 않는다</b> — S-6 · Q-6.
 */
public class LlmTransientException extends LlmException {

    public LlmTransientException(LlmFailureReason reason, LlmCallSite callSite) {
        super(reason, callSite, null);
        if (!reason.isRetryable()) {
            throw new IllegalArgumentException(
                    "재시도 불가 사유로 transient 예외를 만들 수 없다: " + reason);
        }
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
