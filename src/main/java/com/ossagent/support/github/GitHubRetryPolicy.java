package com.ossagent.support.github;

import java.time.Duration;

/**
 * 전송 계층 재시도 판정 — 순수 함수. 부작용도 대기도 여기서 하지 않는다.
 *
 * <p>🔴 <b>이 상한은 {@code agent.execution.max-retries} 와 다른 축이다.</b>
 * 후자는 PRD §17 의 「구현→테스트」 루프 카운터이고 {@code AgentRun.attempt} 에 기록된다.
 * HTTP 5xx 한 번으로 그 카운터를 태우면 후보가 코드 문제 없이 {@code FAILED} 로 떨어진다.
 * 그래서 별도 키 {@code github.max-retries} 를 쓴다 — 재시도 단위의 정의는 미결(Q-6)이다.
 *
 * <p><b>재시도하는 것은 {@link GitHubTransientException} 뿐이다.</b>
 *
 * <ul>
 *   <li>{@link GitHubRateLimitException} — 재시도가 아니라 <b>지연</b>이다. 다시 걸면 예산만 더 태운다
 *   <li>{@link GitHubPermissionException} · {@link GitHubAuthenticationException} — 몇 번을 걸어도 같다
 *   <li>{@link GitHubResourceNotFoundException} — 없는 것은 계속 없다
 * </ul>
 */
public class GitHubRetryPolicy {

    private final int maxRetries;
    private final Duration backoff;

    public GitHubRetryPolicy(int maxRetries, Duration backoff) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("재시도 상한은 음수일 수 없습니다: " + maxRetries);
        }
        if (backoff == null || backoff.isNegative()) {
            throw new IllegalArgumentException("백오프는 0 이상이어야 합니다");
        }
        this.maxRetries = maxRetries;
        this.backoff = backoff;
    }

    public static GitHubRetryPolicy from(GitHubProperties properties) {
        return new GitHubRetryPolicy(properties.maxRetries(), properties.retryBackoff());
    }

    /**
     * @param failure         직전 시도의 실패
     * @param completedRetries 지금까지 <b>재시도한</b> 횟수 (첫 실패 직후에는 0)
     */
    public boolean shouldRetry(GitHubApiException failure, int completedRetries) {
        return failure instanceof GitHubTransientException && completedRetries < maxRetries;
    }

    /** 다음 시도까지의 대기. 고정 백오프의 배수로 늘린다 — 지터는 호출량이 생기면 그때 넣는다. */
    public Duration backoffFor(int completedRetries) {
        return backoff.multipliedBy(completedRetries + 1L);
    }

    public int maxRetries() {
        return maxRetries;
    }
}
