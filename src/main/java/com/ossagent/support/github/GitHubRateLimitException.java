package com.ossagent.support.github;

import java.time.Duration;
import java.time.Instant;

/**
 * 레이트리밋에 걸렸다. <b>재시도가 아니라 지연으로 다뤄야 하는</b> 실패다.
 *
 * <p>리밋 소진은 장애가 아니라 정상 운영 상황이다 —
 * {@code .claude/rules/context/external-deps.md}. 즉시 재시도하면 남은 예산만 더 태우고,
 * 2차 리밋에서는 차단 시간이 길어진다. 그래서 {@link GitHubRetryPolicy} 는 이 타입을
 * 재시도하지 않고, 언제 다시 걸 수 있는지를 {@link #earliestRetryAt(Instant)} 로 알려 줄 뿐이다.
 *
 * <p><b>지연 정책 자체는 여기 없다.</b> 얼마나 기다리고 어느 작업을 미룰지는 호출자가 정한다
 * (이슈 #8 의 증분 수집). 이 예외는 「무엇에 걸렸고 언제 풀리는가」만 싣는다.
 */
public class GitHubRateLimitException extends GitHubApiException {

    /**
     * 어느 리밋인가. 대응 시간대가 다르다.
     *
     * <ul>
     *   <li>{@link #PRIMARY} — 시간당 할당량(인증 5,000/h · Search 30/min). {@code resetAt} 까지 기다린다
     *   <li>{@link #SECONDARY} — abuse detection. <b>429 가 아니라 403 으로 온다.</b>
     *       {@code Retry-After} 를 따르고, 없으면 보수적으로 기다린다
     * </ul>
     */
    public enum Scope {
        PRIMARY,
        SECONDARY
    }

    private final Scope scope;
    private final Instant resetAt;
    private final Duration retryAfter;

    public GitHubRateLimitException(int status, Scope scope, Instant resetAt, Duration retryAfter,
            String message) {
        super(status, message);
        this.scope = scope;
        this.resetAt = resetAt;
        this.retryAfter = retryAfter;
    }

    public Scope scope() {
        return scope;
    }

    /** 1차 리밋의 리셋 시각({@code X-RateLimit-Reset}). 모르면 {@code null}. */
    public Instant resetAt() {
        return resetAt;
    }

    /** 2차 리밋의 {@code Retry-After}. 없으면 {@code null}. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * 다시 호출해도 되는 가장 이른 시각. 두 신호 중 <b>늦은 쪽</b>을 택한다.
     *
     * <p>신호가 하나도 없으면 {@code null} 이다. 호출자는 그때 자기 기본 지연을 쓴다 —
     * 「모르니까 바로 다시 건다」는 선택지는 두지 않는다.
     */
    public Instant earliestRetryAt(Instant now) {
        Instant fromRetryAfter = retryAfter == null ? null : now.plus(retryAfter);
        if (resetAt == null) {
            return fromRetryAfter;
        }
        if (fromRetryAfter == null) {
            return resetAt;
        }
        return fromRetryAfter.isAfter(resetAt) ? fromRetryAfter : resetAt;
    }
}
