package com.ossagent.support.github;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * GitHub REST API 클라이언트 — <b>읽기 전용</b>.
 *
 * <p>🔴 <b>공개 메서드는 {@link #get(GitHubRequest)} 하나뿐이고, 쓰기 동사가 존재하지 않는다.</b>
 * 이것이 S-1(원본 저장소에 쓰지 않는다)을 지키는 방식이다 — 「쓰기를 막는 검사」가 아니라
 * 「쓸 수 있는 메서드가 없음」이다. classic PAT 은 저장소별 권한 제한이 불가능해
 * 토큰 권한으로는 막을 수 없으므로({@code safety-boundaries.md} S-1), 코드 표면이 방어선이 된다.
 * 쓰기가 필요해지는 시점(이슈 #22 Fork push · #23 Draft PR)에 <b>별도 타입</b>을 만들고
 * 거기에 Fork owner 어설션을 붙인다. 이 클래스에 POST 를 더하지 않는다.
 *
 * <p>🔴 <b>토큰은 헤더로만 나간다.</b> URL·쿼리에 싣지 않는다 — 예외 메시지와 로그의 URL 을 타고
 * 나가는 것이 S-4 의 가장 흔한 사고다.
 *
 * <p>Q-1 확정에 따라 Spring {@link RestClient} 로 직접 구현한다. {@code hub4j/github-api} 를
 * 쓰지 않는 이유는 우리가 필요한 3가지(ETag 커서 · 레이트리밋 헤더 · 403 구분)가 전부
 * 직접 제어를 요구하기 때문이다 — {@code open-questions.md} Q-1.
 */
public class GitHubApiClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubApiClient.class);

    private static final String ACCEPT = "application/vnd.github+json";
    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String API_VERSION = "2022-11-28";

    private final RestClient restClient;
    private final GitHubCredentials credentials;
    private final GitHubProperties properties;
    private final GitHubErrorTranslator errorTranslator;
    private final GitHubRetryPolicy retryPolicy;
    private final Clock clock;

    /**
     * 마지막으로 관측한 레이트리밋. 다음 호출을 선제 차단하는 근거다 — {@link #refuseIfBudgetLow}.
     *
     * <p>토큰 단위 전역 예산이라 모든 어댑터가 같은 값을 보는 것이 맞다.
     */
    private volatile GitHubRateLimit lastRateLimit;

    public GitHubApiClient(RestClient restClient, GitHubCredentials credentials,
            GitHubProperties properties, GitHubErrorTranslator errorTranslator, Clock clock) {
        this.restClient = restClient;
        this.credentials = credentials;
        this.properties = properties;
        this.errorTranslator = errorTranslator;
        this.retryPolicy = GitHubRetryPolicy.from(properties);
        this.clock = clock;
        if (credentials.authorizationHeader().isEmpty()) {
            // 값이 아니라 「없다」는 사실만 남긴다 — S-4.
            // 기동을 실패시키지 않는 것은 의도다. 미인증 60 req/h 로 동작하고,
            // 리밋에 부딪히면 GitHubRateLimitException 이라는 정상 경로로 드러난다
            log.warn("GitHub 자격증명이 비어 있습니다 — 미인증 호출(60 req/h)로 동작합니다. GITHUB_TOKEN 을 설정하세요");
        }
    }

    /**
     * GET 요청 한 번. 실패는 타입이 있는 예외로 나간다.
     *
     * <p>재시도 대상은 {@link GitHubTransientException} 뿐이다. 레이트리밋은 재시도하지 않고
     * {@link GitHubRateLimitException} 으로 그대로 던진다 — 지연 정책은 호출자가 정한다(이슈 #8).
     *
     * <p>🔴 <b>임계 미만이면 호출하지 않고 먼저 던진다</b>(#8). 소진된 뒤가 아니라
     * {@code github.rate-limit-threshold} 미만이 되는 순간부터다 — 남은 예산을 다 태우면
     * 같은 토큰을 쓰는 다른 작업(규약 수집 · 코드 검색)이 전부 막힌다.
     *
     * @throws GitHubApiException 및 하위 타입
     */
    public GitHubResponse get(GitHubRequest request) {
        refuseIfBudgetLow(request);
        int completedRetries = 0;
        while (true) {
            try {
                return exchange(request);
            } catch (GitHubApiException e) {
                if (!retryPolicy.shouldRetry(e, completedRetries)) {
                    throw e;
                }
                Duration backoff = retryPolicy.backoffFor(completedRetries);
                completedRetries++;
                log.warn("GitHub 호출 재시도 path={} status={} attempt={}/{} backoffMs={}",
                        request.path(), e.status(), completedRetries, retryPolicy.maxRetries(),
                        backoff.toMillis());
                sleep(backoff);
            }
        }
    }

    private GitHubResponse exchange(GitHubRequest request) {
        long startedAt = clock.millis();
        try {
            return restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(request.path());
                        for (Map.Entry<String, String> param : request.query().entrySet()) {
                            uriBuilder.queryParam(param.getKey(), param.getValue());
                        }
                        return uriBuilder.build();
                    })
                    .headers(this::applyProtocolHeaders)
                    .headers(headers -> {
                        if (request.isConditional()) {
                            headers.setIfNoneMatch(request.ifNoneMatch());
                        }
                    })
                    .exchange((httpRequest, response) -> handle(request, response, startedAt));
        } catch (GitHubApiException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // 연결 거부·타임아웃 — 응답이 없다
            throw errorTranslator.translateIoFailure(request.path(), e);
        } catch (CancellationException e) {
            // 🔴 읽기 타임아웃의 두 번째 경로. RestClientException 계열이 아니라
            //    아래 catch 들을 전부 빠져나간다 — 잡지 않으면 타입 없는 예외가 올라가고
            //    GitHubRetryPolicy 가 보지 못해 「타임아웃인데 재시도 안 됨」이 된다.
            //
            //    Spring 6.2.7 JdkClientHttpRequest.executeInternal 은 ExecutionException 에
            //    감싸져 온 취소만 HttpTimeoutException 으로 바꾼다. TimeoutHandler 가 레이스를
            //    이겨 future 가 이미 취소된 뒤 get() 이 불리면 CancellationException 이 직접
            //    날아오고 그 분기에 걸리지 않는다. 부하가 걸릴수록 자주 탄다.
            throw errorTranslator.translateIoFailure(request.path(), e);
        } catch (RestClientException e) {
            GitHubApiException unwrapped = unwrap(e);
            if (unwrapped != null) {
                throw unwrapped;
            }
            throw new GitHubApiException(GitHubApiException.NO_STATUS,
                    "GitHub 호출 실패 path=" + request.path(), e);
        }
    }

    private GitHubResponse handle(GitHubRequest request, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            long startedAt) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        HttpHeaders headers = response.getHeaders();
        GitHubRateLimit rateLimit = GitHubHeaders.rateLimit(headers);
        String etag = GitHubHeaders.etag(headers);

        logCall(request, status, rateLimit, clock.millis() - startedAt);
        recordRateLimit(request, rateLimit);

        if (status.value() == HttpStatus.NOT_MODIFIED.value()) {
            return GitHubResponse.notModified(etag, rateLimit);
        }
        // 🔴 리다이렉트를 따라가지 않으므로(S-4 · GitHubClientConfig 참조) 3xx 가 여기 온다.
        //    3xx 는 isError() 가 false 라, 잡지 않으면 「Moved Permanently」 본문이
        //    정상 응답으로 둔갑해 어댑터가 빈 메타데이터를 만든다
        if (status.is3xxRedirection()) {
            throw new GitHubApiException(status.value(),
                    "GitHub 이 리다이렉트를 돌려줬습니다 — 대상 저장소가 이동·이름변경됐을 수 있습니다. "
                            + "path=" + request.path() + " location="
                            + headers.getFirst(HttpHeaders.LOCATION));
        }
        if (status.isError()) {
            throw errorTranslator.translate(status.value(), headers, readBody(response), request.path());
        }
        return GitHubResponse.of(response.bodyTo(JsonNode.class), etag,
                headers.getFirst(HttpHeaders.LINK), rateLimit);
    }

    private void applyProtocolHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.ACCEPT, ACCEPT);
        headers.set(API_VERSION_HEADER, API_VERSION);
        // 🔴 호출마다 공급자에게 묻는다 — 단수명 자격증명(user-to-server·설치 토큰)으로
        //    갈아끼울 수 있어야 한다(Q-1 「남은 것」, #6). 값을 생성 시점에 구워 넣지 않는다.
        // 🔴 헤더로만 보낸다. 쿼리 파라미터로 보내면 URL 이 담기는 모든 곳으로 유출된다 — S-4
        credentials.authorizationHeader()
                .ifPresent(value -> headers.set(HttpHeaders.AUTHORIZATION, value));
    }

    /**
     * 대외 호출 기록 — 대상 · 소요 시간 · 결과 코드. 「레이턴시는 전부 기록」이 규칙이다
     * ({@code .claude/rules/conventions/logging.md}). 본문·헤더 원문은 남기지 않는다.
     */
    private void logCall(GitHubRequest request, HttpStatusCode status, GitHubRateLimit rateLimit,
            long elapsedMs) {
        log.debug("GitHub 호출 path={} status={} elapsedMs={} rateRemaining={} conditional={}",
                request.path(), status.value(), elapsedMs, rateLimit.remaining(),
                request.isConditional());
    }

    /**
     * 관측한 레이트리밋을 기억하고 경고를 남긴다.
     *
     * <p>🔴 <b>여기서 던지지 않는다.</b> 이 메서드는 응답을 <b>이미 받은 뒤</b>에 불린다 —
     * 여기서 던지면 방금 받아온 페이지를 버리게 된다. 리밋을 아끼려다 이미 지불한 호출을
     * 낭비하는 셈이다.
     *
     * <p>대신 값을 기억해 두고 {@link #refuseIfBudgetLow(GitHubRequest)} 가
     * <b>다음 호출 진입부</b>에서 막는다. 받아온 것은 다 쓰고, 다음 호출을 멈춘다.
     */
    private void recordRateLimit(GitHubRequest request, GitHubRateLimit rateLimit) {
        if (rateLimit.isKnown()) {
            lastRateLimit = rateLimit;
        }
        if (!rateLimit.isBelow(properties.rateLimitThreshold())) {
            return;
        }
        log.warn("GitHub 레이트리밋 임박 path={} remaining={} limit={} resetAt={} threshold={}",
                request.path(), rateLimit.remaining(), rateLimit.limit(), rateLimit.resetAt(),
                properties.rateLimitThreshold());
    }

    /**
     * 🔴 남은 예산이 임계 미만이면 <b>호출하지 않고</b> 던진다 — #8 완료 조건 3.
     *
     * <p>소진된 뒤가 아니라 {@code github.rate-limit-threshold} 미만이 되는 순간부터다.
     * 남은 예산을 끝까지 태우면 <b>같은 토큰을 쓰는 다른 작업</b>(규약 수집 #7 · 코드 검색 #15)이
     * 전부 막힌다. 스캐너가 가장 많이 호출하므로 스캐너가 먼저 양보한다.
     *
     * <p>⚠ 리셋 시각이 지났으면 예산이 다시 찼으므로 통과시킨다. 기억한 값은 그대로 두고
     * 다음 응답이 갱신한다 — 여기서 지우면 리셋 직후 첫 호출이 판단 근거를 잃는다.
     *
     * <p>상태를 클라이언트가 들고 있는 것은 의도다. <b>GitHub 레이트리밋은 토큰 단위 전역</b>이라
     * 어느 어댑터가 태운 예산이든 같은 예산이다. {@code volatile} 이면 충분하다 —
     * 놓친 갱신이 있어도 다음 응답이 바로잡고, 경계에서 한 번 더 호출되는 것은 해롭지 않다.
     */
    private void refuseIfBudgetLow(GitHubRequest request) {
        GitHubRateLimit observed = lastRateLimit;
        if (observed == null || !observed.isBelow(properties.rateLimitThreshold())) {
            return;
        }
        Instant resetAt = observed.resetAt();
        if (resetAt != null && !clock.instant().isBefore(resetAt)) {
            return;   // 리셋이 지났다 — 예산이 다시 찼다
        }
        log.warn("GitHub 레이트리밋 임계 미만 — 호출하지 않고 지연 path={} remaining={} threshold={} resetAt={}",
                request.path(), observed.remaining(), properties.rateLimitThreshold(), resetAt);
        throw new GitHubRateLimitException(GitHubApiException.NO_STATUS,
                GitHubRateLimitException.Scope.PRIMARY, resetAt, null,
                "GitHub 레이트리밋 임계 미만이라 호출하지 않았습니다 path=" + request.path()
                        + " remaining=" + observed.remaining());
    }

    private static String readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            return StreamUtils.copyToString(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code exchange} 안에서 던진 우리 예외가 RestClient 예외로 감싸여 올라온 경우를 되돌린다. */
    private static GitHubApiException unwrap(Throwable e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof GitHubApiException gitHubApiException) {
                return gitHubApiException;
            }
        }
        return null;
    }

    private static void sleep(Duration backoff) {
        if (backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException(GitHubApiException.NO_STATUS, "GitHub 재시도 대기 중 중단됐습니다", e);
        }
    }
}
