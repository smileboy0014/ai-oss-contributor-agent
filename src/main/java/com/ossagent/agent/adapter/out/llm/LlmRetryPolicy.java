package com.ossagent.agent.adapter.out.llm;

import com.ossagent.agent.domain.LlmException;
import com.ossagent.agent.domain.LlmTransientException;
import java.time.Duration;

/**
 * 전송 계층 재시도 판정 — 순수 함수. 부작용도 대기도 여기서 하지 않는다.
 * {@code GitHubRetryPolicy} 와 같은 모양이다.
 *
 * <p>🔴 <b>이 상한은 {@code agent.execution.max-retries} 와 다른 축이다.</b>
 * 후자는 Q-6 이 확정한 {@code CODE → VERIFY → REVIEW} 한 바퀴를 세고 {@code AgentRun.attempt} 에
 * 기록된다. 타임아웃 한 번으로 그 카운터를 태우면 후보가 코드 문제 없이 {@code FAILED} 로
 * 떨어지고, 사람은 「AI 가 못 고쳤다」로 읽는다. 그래서 별도 키 {@code agent.llm.max-retries} 를 쓴다.
 *
 * <p><b>재시도하는 것은 {@link LlmTransientException} 뿐이다.</b>
 *
 * <ul>
 *   <li>거부 — 몇 번을 걸어도 같은 답이 온다
 *   <li>절단 — 같은 상한으로 재전송하면 <b>같은 지점에서 잘린다.</b> 입력 토큰만 배로 태우고
 *       결과는 같다. 게다가 이것은 전송 실패가 아니라 출력이 예산을 넘은 것이고,
 *       고칠 주체는 어댑터가 아니라 호출자다
 *   <li>잘못된 요청 · 인증 실패 — 재시도하면 무한 루프가 된다
 * </ul>
 */
public class LlmRetryPolicy {

    private final int maxRetries;
    private final Duration backoff;

    public LlmRetryPolicy(int maxRetries, Duration backoff) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("재시도 상한은 음수일 수 없습니다: " + maxRetries);
        }
        if (backoff == null || backoff.isNegative()) {
            throw new IllegalArgumentException("백오프는 0 이상이어야 합니다");
        }
        this.maxRetries = maxRetries;
        this.backoff = backoff;
    }

    public static LlmRetryPolicy from(AnthropicProperties properties) {
        return new LlmRetryPolicy(properties.maxRetries(), properties.retryBackoff());
    }

    /**
     * @param failure          직전 시도의 실패
     * @param completedRetries 지금까지 <b>재시도한</b> 횟수 (첫 실패 직후에는 0)
     */
    public boolean shouldRetry(LlmException failure, int completedRetries) {
        return failure instanceof LlmTransientException && completedRetries < maxRetries;
    }

    /** 다음 시도까지의 대기. 고정 백오프의 배수로 늘린다 — 지터는 호출량이 생기면 그때 넣는다. */
    public Duration backoffFor(int completedRetries) {
        return backoff.multipliedBy(completedRetries + 1L);
    }

    public int maxRetries() {
        return maxRetries;
    }
}
