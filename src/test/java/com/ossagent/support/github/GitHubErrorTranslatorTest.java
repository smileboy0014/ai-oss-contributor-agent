package com.ossagent.support.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * 403 구분 — 이 프로젝트에서 가장 값비싼 오판이 일어나는 자리.
 *
 * <p>2차 레이트리밋을 권한 오류로 처리하면 정상 운영 상황이 영구 실패가 되고,
 * 권한 오류를 레이트리밋으로 처리하면 고쳐지지 않을 요청을 계속 다시 건다 —
 * {@code .claude/rules/context/external-deps.md}.
 */
class GitHubErrorTranslatorTest {

    private static final Instant NOW = Instant.parse("2026-09-22T09:00:00Z");
    private static final Instant RESET_AT = Instant.parse("2026-09-22T10:00:00Z");
    private static final String PATH = "/repos/spring-projects/spring-kafka/issues";

    private final GitHubErrorTranslator translator =
            new GitHubErrorTranslator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("403 + Remaining 0 은 1차 레이트리밋이다 — 권한 오류가 아니다")
    void 할당량_소진_403은_1차_레이트리밋이다() {
        HttpHeaders headers = rateLimitHeaders(5000, 0, RESET_AT);

        GitHubApiException e = translator.translate(403, headers, "{\"message\":\"API rate limit exceeded\"}", PATH);

        assertThat(e)
                .as("할당량 소진을 권한 오류로 보면 리밋이 풀려도 영원히 실패한다")
                .isInstanceOf(GitHubRateLimitException.class);
        GitHubRateLimitException limit = (GitHubRateLimitException) e;
        assertThat(limit.scope()).isEqualTo(GitHubRateLimitException.Scope.PRIMARY);
        assertThat(limit.resetAt()).isEqualTo(RESET_AT);
    }

    @Test
    @DisplayName("403 + Retry-After 는 2차 레이트리밋이다 — 429 로 오지 않는다")
    void Retry_After가_있는_403은_2차_레이트리밋이다() {
        HttpHeaders headers = rateLimitHeaders(5000, 4321, RESET_AT);
        headers.set("Retry-After", "60");

        GitHubApiException e = translator.translate(403, headers, "", PATH);

        assertThat(e)
                .as("2차 리밋(abuse detection)은 429 가 아니라 403 으로 온다")
                .isInstanceOf(GitHubRateLimitException.class);
        GitHubRateLimitException limit = (GitHubRateLimitException) e;
        assertThat(limit.scope()).isEqualTo(GitHubRateLimitException.Scope.SECONDARY);
        assertThat(limit.retryAfter()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("헤더가 없어도 본문 문구로 2차 레이트리밋을 알아본다")
    void 본문_문구로_2차_레이트리밋을_판정한다() {
        String body = "{\"message\":\"You have exceeded a secondary rate limit.\"}";

        GitHubApiException e = translator.translate(403, HttpHeaders.EMPTY, body, PATH);

        assertThat(e).isInstanceOf(GitHubRateLimitException.class);
        assertThat(((GitHubRateLimitException) e).scope())
                .isEqualTo(GitHubRateLimitException.Scope.SECONDARY);
    }

    @Test
    @DisplayName("레이트리밋 신호가 하나도 없는 403 은 권한 오류다")
    void 신호_없는_403은_권한오류다() {
        HttpHeaders headers = rateLimitHeaders(5000, 4999, RESET_AT);
        String body = "{\"message\":\"Resource not accessible by personal access token\"}";

        GitHubApiException e = translator.translate(403, headers, body, PATH);

        assertThat(e)
                .as("애매하면 권한 오류로 떨어뜨린다 — 오판의 결과가 무한 재시도가 아니라 빠른 실패가 되도록")
                .isInstanceOf(GitHubPermissionException.class);
    }

    @Test
    @DisplayName("429 는 레이트리밋 신호가 없어도 2차 레이트리밋으로 본다")
    void 상태코드_429는_2차_레이트리밋이다() {
        GitHubApiException e = translator.translate(429, HttpHeaders.EMPTY, "", PATH);

        assertThat(e).isInstanceOf(GitHubRateLimitException.class);
        assertThat(((GitHubRateLimitException) e).scope())
                .isEqualTo(GitHubRateLimitException.Scope.SECONDARY);
    }

    @Test
    @DisplayName("Retry-After 를 HTTP-date 로 줘도 읽는다")
    void Retry_After가_날짜여도_읽는다() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "Tue, 22 Sep 2026 09:02:00 GMT");

        GitHubApiException e = translator.translate(403, headers, "", PATH);

        assertThat(e).isInstanceOf(GitHubRateLimitException.class);
        assertThat(((GitHubRateLimitException) e).retryAfter())
                .as("날짜 형식을 버리면 2차 리밋을 권한 오류로 오판한다")
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void 인증실패_401을_구분한다() {
        assertThat(translator.translate(401, HttpHeaders.EMPTY, "", PATH))
                .isInstanceOf(GitHubAuthenticationException.class);
    }

    @Test
    void 없는_리소스_404를_구분한다() {
        assertThat(translator.translate(404, HttpHeaders.EMPTY, "", PATH))
                .isInstanceOf(GitHubResourceNotFoundException.class);
    }

    @Test
    @DisplayName("5xx 만 재시도 대상 타입으로 나온다")
    void 서버오류는_일시적_실패다() {
        assertThat(translator.translate(500, HttpHeaders.EMPTY, "", PATH))
                .isInstanceOf(GitHubTransientException.class);
        assertThat(translator.translate(502, HttpHeaders.EMPTY, "", PATH))
                .isInstanceOf(GitHubTransientException.class);
        assertThat(translator.translate(422, HttpHeaders.EMPTY, "", PATH))
                .as("4xx 를 일시적 실패로 보면 고쳐지지 않을 요청을 계속 다시 건다")
                .isNotInstanceOf(GitHubTransientException.class);
    }

    @Test
    @DisplayName("연결 실패는 응답이 없어도 재시도 대상이다")
    void 연결실패는_재시도_대상이다() {
        GitHubTransientException e =
                translator.translateIoFailure(PATH, new java.net.SocketTimeoutException("read timed out"));

        assertThat(e.status()).isEqualTo(GitHubApiException.NO_STATUS);
    }

    @Test
    @DisplayName("응답 본문에 토큰이 섞여 있어도 예외 메시지로 나가지 않는다")
    void 본문에_섞인_토큰이_예외로_새지_않는다_S4() {
        String leaked = "ghp_" + "z".repeat(30);
        String body = "{\"message\":\"bad credentials " + leaked + "\"}";

        GitHubApiException e = translator.translate(500, HttpHeaders.EMPTY, body, PATH);

        assertThat(e.getMessage())
                .as("예외 메시지는 로그·AgentRun.errorMessage 로 번지는 경로다")
                .doesNotContain(leaked);
    }

    @Test
    @DisplayName("긴 응답 본문은 잘라서 싣는다")
    void 긴_본문은_잘라_싣는다() {
        String body = "x".repeat(5000);

        GitHubApiException e = translator.translate(500, HttpHeaders.EMPTY, body, PATH);

        assertThat(e.getMessage())
                .as("payload 전문을 로그에 남기면 로그가 저장소가 된다")
                .hasSizeLessThan(600)
                .contains("(잘림)");
    }

    @Test
    @DisplayName("깨진 레이트리밋 헤더 때문에 호출이 실패하지는 않는다")
    void 깨진_헤더는_모름으로_처리한다() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Remaining", "알수없음");
        headers.set("X-RateLimit-Reset", "not-a-number");

        GitHubApiException e = translator.translate(403, headers, "", PATH);

        assertThat(e)
                .as("헤더 파싱 실패가 예외로 번지면 원인이 엉뚱한 곳에서 드러난다")
                .isInstanceOf(GitHubPermissionException.class);
    }

    private static HttpHeaders rateLimitHeaders(int limit, int remaining, Instant resetAt) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(limit));
        headers.set("X-RateLimit-Remaining", String.valueOf(remaining));
        headers.set("X-RateLimit-Reset", String.valueOf(resetAt.getEpochSecond()));
        return headers;
    }
}
