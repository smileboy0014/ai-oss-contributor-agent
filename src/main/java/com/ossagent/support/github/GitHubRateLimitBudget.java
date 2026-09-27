package com.ossagent.support.github;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 토큰 단위 레이트리밋 예산 — <b>모든 GitHub 어댑터가 공유한다</b>.
 *
 * <h2>왜 클라이언트 밖으로 뺐나</h2>
 *
 * <p>원래 {@link GitHubApiClient} 의 인스턴스 필드였고, javadoc 에는
 * 「토큰 단위 전역 예산이라 <b>모든 어댑터가 같은 값을 보는 것이 맞다</b>」고 적혀 있었다.
 * 클라이언트가 하나뿐일 때는 그 말이 참이었다.
 *
 * <p>🔴 <b>#22 가 쓰기 클라이언트를 만들면서 거짓이 된다.</b> 각자 자기 필드를 들면
 * 읽기 쪽이 태운 예산을 쓰기 쪽이 모르고, 임계 미만인데도 계속 호출한다 —
 * 「같은 토큰을 쓰는 다른 작업이 전부 막힌다」를 막으려던 장치가 <b>둘로 갈려 무력해진다.</b>
 * 그래서 값을 한 곳에 두고 양쪽이 주입받는다.
 *
 * <p>{@code volatile} 이면 충분하다 — 놓친 갱신이 있어도 다음 응답이 바로잡고,
 * 경계에서 한 번 더 호출되는 것은 해롭지 않다.
 */
public class GitHubRateLimitBudget {

    private static final Logger log = LoggerFactory.getLogger(GitHubRateLimitBudget.class);

    private final Clock clock;

    /** 마지막으로 관측한 레이트리밋. 다음 호출을 선제 차단하는 근거다. */
    private volatile GitHubRateLimit lastRateLimit;

    public GitHubRateLimitBudget(Clock clock) {
        this.clock = clock;
    }

    /**
     * 관측한 레이트리밋을 기억하고 경고를 남긴다.
     *
     * <p>🔴 <b>여기서 던지지 않는다.</b> 이 메서드는 응답을 <b>이미 받은 뒤</b>에 불린다 —
     * 여기서 던지면 방금 받아온 페이지를 버리게 된다. 리밋을 아끼려다 이미 지불한 호출을
     * 낭비하는 셈이다.
     *
     * <p>대신 값을 기억해 두고 {@link #refuseIfBudgetLow} 가 <b>다음 호출 진입부</b>에서 막는다.
     * 받아온 것은 다 쓰고, 다음 호출을 멈춘다.
     */
    /**
     * 응답 헤더에서 직접 읽어 기록한다.
     *
     * <p>🔴 {@code GitHubHeaders} 는 <b>package-private</b> 이고 그대로 둔다. 헤더 파싱은
     * {@code support/github} 의 내부 관심사이고, 공개하면 각 도메인 어댑터가 저마다
     * 파싱하게 되어 「레이트리밋 판정이 어디 있는가」가 흩어진다. 다른 패키지의 클라이언트는
     * 이 메서드로 넘긴다.
     */
    public void recordFrom(String path, org.springframework.http.HttpHeaders headers, int threshold) {
        record(path, GitHubHeaders.rateLimit(headers), threshold);
    }

    public void record(String path, GitHubRateLimit rateLimit, int threshold) {
        if (rateLimit.isKnown()) {
            lastRateLimit = rateLimit;
        }
        if (!rateLimit.isBelow(threshold)) {
            return;
        }
        log.warn("GitHub 레이트리밋 임박 path={} remaining={} limit={} resetAt={} threshold={}",
                path, rateLimit.remaining(), rateLimit.limit(), rateLimit.resetAt(), threshold);
    }

    /**
     * 🔴 남은 예산이 임계 미만이면 <b>호출하지 않고</b> 던진다 — #8 완료 조건 3.
     *
     * <p>소진된 뒤가 아니라 {@code github.rate-limit-threshold} 미만이 되는 순간부터다.
     * 남은 예산을 끝까지 태우면 <b>같은 토큰을 쓰는 다른 작업</b>(규약 수집 #7 · 코드 검색 #15 ·
     * Fork push #22)이 전부 막힌다 — 마지막 예산은 아무도 못 쓰는 것보다 아무도 안 쓰는 편이 낫다.
     *
     * <p>⚠ <b>호출자를 구분하지 않는다.</b> 임계가 하나뿐이라 스캐너든 규약 수집이든 쓰기든
     * <b>똑같이</b> 거절된다. 「스캐너가 먼저 양보한다」 같은 우선순위는 구현돼 있지 않다.
     *
     * <p>⚠ 리셋 시각이 지났으면 예산이 다시 찼으므로 통과시킨다. 기억한 값은 그대로 두고
     * 다음 응답이 갱신한다 — 여기서 지우면 리셋 직후 첫 호출이 판단 근거를 잃는다.
     *
     * <p>🔴 <b>{@code resetAt} 을 모르면 막지 않는다.</b> 막아 버리면 스스로 빠져나올 수 없다 —
     * 이 값은 <b>응답을 받아야</b> 갱신되는데, 막는 동안에는 호출이 나가지 않아
     * <b>재기동 전까지 모든 GitHub 호출이 영구히 실패</b>한다. 방어가 자폭이 되는 자리다.
     */
    public void refuseIfBudgetLow(String path, int threshold) {
        GitHubRateLimit observed = lastRateLimit;
        if (observed == null || !observed.isBelow(threshold)) {
            return;
        }
        Instant resetAt = observed.resetAt();
        if (resetAt == null) {
            log.warn("GitHub 레이트리밋 임계 미만이나 resetAt 을 모른다 — 차단하지 않는다 path={} remaining={}",
                    path, observed.remaining());
            return;
        }
        if (!clock.instant().isBefore(resetAt)) {
            return;   // 리셋이 지났다 — 예산이 다시 찼다
        }
        log.warn("GitHub 레이트리밋 임계 미만 — 호출하지 않고 지연 path={} remaining={} threshold={} resetAt={}",
                path, observed.remaining(), threshold, resetAt);
        throw new GitHubRateLimitException(GitHubApiException.NO_STATUS,
                GitHubRateLimitException.Scope.PRIMARY, resetAt, null,
                "GitHub 레이트리밋 임계 미만이라 호출하지 않았습니다 path=" + path
                        + " remaining=" + observed.remaining());
    }
}
