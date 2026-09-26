package com.ossagent.support.observability;

/**
 * LLM 호출 1건의 결과.
 *
 * <p>⚠️ {@code LlmFailureReason} 을 그대로 태그로 쓰지 않는다. 6값이고 앞으로 늘 수 있는데,
 * 여기서 필요한 것은 <b>「성공했나」</b>뿐이다. 세부 사유는 {@code AgentRun} 에 영속되고
 * 로그에 남는다 — 메트릭 태그는 <b>유한하고 안정적인 어휘</b>여야 한다.
 */
public enum LlmOutcome {
    SUCCEEDED,
    FAILED
}
