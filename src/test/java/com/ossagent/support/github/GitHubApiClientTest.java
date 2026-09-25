package com.ossagent.support.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ossagent.support.secret.TokenRedactor;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 클라이언트가 안전 경계를 실제로 지키는지.
 *
 * <p>대외 호출을 흉내 내는 수단은 {@code MockRestServiceServer}(spring-test 내장)다.
 * <b>네트워크를 타지 않는다</b> — 실제 GitHub 를 타는 자동 테스트는 만들지 않는다
 * ({@code .claude/rules/conventions/testing-philosophy.md}).
 *
 * <p>⚠ 토큰 유사 문자열은 소스에 리터럴로 두지 않고 런타임에 조립한다.
 */
class GitHubApiClientTest {

    private static final String BASE_URL = "https://api.github.test";
    private static final String FAKE_TOKEN = "ghp_" + "t".repeat(30);
    private static final Instant NOW = Instant.parse("2026-09-22T09:00:00Z");
    private static final Instant RESET_AT = Instant.parse("2026-09-22T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private MockRestServiceServer server;
    private GitHubApiClient client;
    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger clientLogger;

    @BeforeEach
    void setUp() {
        // 백오프 0 — 재시도 동작을 검증하되 테스트가 실제로 자지는 않게 한다
        client = clientWith(properties(FAKE_TOKEN, 2, Duration.ZERO, 100));

        clientLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GitHubApiClient.class);
        logs = new ListAppender<>();
        logs.start();
        clientLogger.addAppender(logs);
        clientLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logs);
    }

    // ── 전송 실패 번역 ──────────────────────────────────────────────────

    @Test
    @DisplayName("취소로 올라온 읽기 타임아웃도 전송 실패로 번역되고 재시도된다")
    void 취소된_요청은_전송_실패로_번역된다() {
        AtomicInteger calls = new AtomicInteger();
        GitHubProperties properties = properties(FAKE_TOKEN, 2, Duration.ZERO, 100);
        RestClient cancelling = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory((uri, httpMethod) -> {
                    calls.incrementAndGet();
                    throw new CancellationException("Request cancelled by TimeoutHandler");
                })
                .build();
        GitHubApiClient candidate = new GitHubApiClient(cancelling,
                StaticTokenCredentials.from(properties), properties,
                new GitHubErrorTranslator(clock), clock);

        assertThatThrownBy(() -> candidate.get(GitHubRequest.of("/repos/spring-projects/spring-kafka")))
                .as("CancellationException 은 RestClientException 계열이 아니라 catch 를 전부 "
                        + "빠져나간다. 잡지 않으면 타입 없는 예외가 올라간다")
                .isInstanceOf(GitHubTransientException.class);

        assertThat(calls.get())
                .as("이것이 본체다 — 번역되지 않으면 재시도 루프(GitHubApiException 만 잡는다)를 "
                        + "빠져나가 1회로 끝난다. 「타임아웃인데 재시도 안 됨」이 그 증상이다")
                .isEqualTo(3);
    }

    // ── S-1 · 읽기 전용 표면 ────────────────────────────────────────────

    @Test
    @DisplayName("클라이언트에 쓰기 메서드가 존재하지 않는다")
    void 쓰기_메서드를_노출하지_않는다_S1() {
        List<String> writeVerbs = List.of("post", "put", "patch", "delete", "create", "update",
                "push", "merge", "fork");

        List<String> publicMethods = Arrays.stream(GitHubApiClient.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .toList();

        assertThat(publicMethods)
                .as("classic PAT 은 저장소별 권한 제한이 불가능하다. 쓰기 메서드가 없다는 것이 방어선이다 — S-1")
                .containsExactly("get");
        assertThat(publicMethods)
                .as("쓰기 동사를 가진 메서드가 생기면 이 방어선이 무너진다. 쓰기는 #22·#23 의 별도 타입에서 어설션과 함께")
                .noneMatch(name -> writeVerbs.contains(name.toLowerCase(Locale.ROOT)));
    }

    @Test
    @DisplayName("실제로 보내는 HTTP 동사는 GET 뿐이다")
    void GET만_보낸다_S1() {
        server.expect(once(), requestTo(BASE_URL + "/repos/spring-projects/spring-kafka"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"name\":\"spring-kafka\"}", MediaType.APPLICATION_JSON));

        client.get(GitHubRequest.of("/repos/spring-projects/spring-kafka"));

        server.verify();
    }

    // ── S-4 · 토큰 유출 ────────────────────────────────────────────────

    @Test
    @DisplayName("토큰은 헤더로만 나가고 URL 에는 남지 않는다")
    void 토큰은_헤더로만_보낸다_S4() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n?per_page=100"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + FAKE_TOKEN))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get(GitHubRequest.of("/repos/o/n").withQuery("per_page", "100"));

        // requestTo 가 정확히 일치해야 통과하므로, URL 에 토큰이 붙었다면 여기서 깨진다
        server.verify();
    }

    @Test
    @DisplayName("실패 응답의 예외 사슬 어디에도 토큰이 없다")
    void 예외_메시지에_토큰이_실리지_않는다_S4() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"message\":\"Resource not accessible\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        Throwable thrown = org.assertj.core.api.Assertions
                .catchThrowable(() -> client.get(GitHubRequest.of("/repos/o/n")));

        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage()))
                    .as("예외 메시지는 로그·AgentRun.errorMessage·에러 응답으로 번진다")
                    .doesNotContain(FAKE_TOKEN);
        }
    }

    @Test
    @DisplayName("응답 본문에 실려 온 토큰이 예외로 나가지 않는다 — 스크럽 배선을 고정한다")
    void 응답_본문의_토큰이_예외로_새지_않는다_S4() {
        // 앞의 두 테스트만으로는 부족하다. 403 본문에 토큰이 없으면
        // TokenRedactor 를 통째로 지워도 통과한다. 토큰이 실제로 메시지에 들어가는
        // 입력을 줘서 「본문 → 발췌 → 예외 생성자」 배선 전체를 고정한다
        // 422 를 쓰는 이유 — 5xx 는 재시도 대상이라 기대를 3번 걸어야 한다.
        // 여기서 보려는 것은 재시도가 아니라 스크럽이다
        String leaked = "ghp_" + "w".repeat(30);
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("upstream said: " + leaked));

        Throwable thrown = org.assertj.core.api.Assertions
                .catchThrowable(() -> client.get(GitHubRequest.of("/repos/o/n")));

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
        assertThat(thrown.getMessage())
                .as("대상 저장소·GitHub 이 준 본문에 시크릿이 섞여 있을 수 있다")
                .doesNotContain(leaked)
                .contains(TokenRedactor.MASK);
    }

    @Test
    @DisplayName("로그 어디에도 토큰이 남지 않는다")
    void 로그에_토큰이_남지_않는다_S4() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{}"));

        org.assertj.core.api.Assertions
                .catchThrowable(() -> client.get(GitHubRequest.of("/repos/o/n")));

        assertThat(logs.list)
                .as("로그는 수집기·백업으로 복제된다. 한 번 나가면 회수는 폐기·재발급뿐이다")
                .noneMatch(event -> event.getFormattedMessage().contains(FAKE_TOKEN));
    }

    @Test
    @DisplayName("자격증명이 없으면 인증 헤더를 붙이지 않고 미인증으로 호출한다")
    void 자격증명이_없으면_헤더를_생략한다() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer anonymousServer = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient anonymous = new GitHubApiClient(builder.build(),
                new StaticTokenCredentials(""), properties("", 0, Duration.ZERO, 100),
                new GitHubErrorTranslator(clock), clock);

        anonymousServer.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        anonymous.get(GitHubRequest.of("/repos/o/n"));

        anonymousServer.verify();
    }

    // ── 레이트리밋 노출 ────────────────────────────────────────────────

    @Test
    @DisplayName("레이트리밋 헤더를 응답에 실어 노출한다")
    void 레이트리밋_헤더를_노출한다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON)
                        .headers(rateLimitHeaders(5000, 4321, RESET_AT)));

        GitHubResponse response = client.get(GitHubRequest.of("/repos/o/n"));

        assertThat(response.rateLimit().limit()).isEqualTo(5000);
        assertThat(response.rateLimit().remaining()).isEqualTo(4321);
        assertThat(response.rateLimit().resetAt()).isEqualTo(RESET_AT);
    }

    @Test
    @DisplayName("임계 미만이면 경고를 남기되 실패시키지 않는다")
    void 레이트리밋_임박은_경고일_뿐_실패가_아니다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON)
                        .headers(rateLimitHeaders(5000, 7, RESET_AT)));

        GitHubResponse response = client.get(GitHubRequest.of("/repos/o/n"));

        assertThat(response.notModified()).isFalse();
        assertThat(logs.list)
                .as("리밋 소진은 정상 운영 상황이다. 실패시키지 않고 지연할 수 있게 알린다")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("레이트리밋 임박"));
    }

    @Test
    @DisplayName("헤더가 없으면 「넉넉함」이 아니라 「모름」이다")
    void 레이트리밋_헤더가_없으면_모름이다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GitHubResponse response = client.get(GitHubRequest.of("/repos/o/n"));

        assertThat(response.rateLimit().isKnown()).isFalse();
        assertThat(response.rateLimit().isBelow(100))
                .as("모르는 것을 임박으로 취급하면 헤더 없는 응답마다 지연이 걸린다")
                .isFalse();
    }

    // ── 조건부 요청 ────────────────────────────────────────────────────

    @Test
    @DisplayName("304 는 실패가 아니라 「바뀐 것이 없다」다")
    void 조건부_요청의_304를_값으로_돌려준다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"abc\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED)
                        .headers(rateLimitHeaders(5000, 4999, RESET_AT)));

        GitHubResponse response =
                client.get(GitHubRequest.of("/repos/o/n").withIfNoneMatch("\"abc\""));

        assertThat(response.notModified()).isTrue();
        assertThat(response.hasBody()).isFalse();
        assertThat(response.rateLimit().remaining()).isEqualTo(4999);
    }

    // ── 재시도 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("5xx 는 상한 안에서 다시 건다")
    void 일시적_실패는_재시도한다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("bad gateway"));
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        GitHubResponse response = client.get(GitHubRequest.of("/repos/o/n"));

        assertThat(response.body().path("ok").asBoolean()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("상한을 소진하면 마지막 실패를 전파한다")
    void 재시도_상한을_넘으면_전파한다() {
        for (int i = 0; i < 3; i++) {
            server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("down"));
        }

        assertThatThrownBy(() -> client.get(GitHubRequest.of("/repos/o/n")))
                .as("상한이 없으면 장애 시 호출량이 폭증한다")
                .isInstanceOf(GitHubTransientException.class);

        server.verify();
    }

    @Test
    @DisplayName("레이트리밋은 다시 걸지 않는다 — 남은 예산만 더 태운다")
    void 레이트리밋은_재시도하지_않는다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .headers(rateLimitHeaders(5000, 0, RESET_AT))
                        .body("{\"message\":\"API rate limit exceeded\"}"));

        assertThatThrownBy(() -> client.get(GitHubRequest.of("/repos/o/n")))
                .isInstanceOf(GitHubRateLimitException.class)
                .satisfies(e -> assertThat(((GitHubRateLimitException) e).resetAt())
                        .isEqualTo(RESET_AT));

        // 한 번만 불렀다 — 기대가 하나뿐인데 두 번 불렀다면 verify 가 깨진다
        server.verify();
    }

    @Test
    @DisplayName("권한 오류는 다시 걸지 않는다")
    void 권한오류는_재시도하지_않는다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .headers(rateLimitHeaders(5000, 4999, RESET_AT))
                        .body("{\"message\":\"Resource not accessible\"}"));

        assertThatThrownBy(() -> client.get(GitHubRequest.of("/repos/o/n")))
                .isInstanceOf(GitHubPermissionException.class);

        server.verify();
    }

    @Test
    @DisplayName("리다이렉트를 따라가지 않고 명시적 실패로 만든다")
    void 리다이렉트를_따라가지_않는다_S4() {
        // 리다이렉트를 켜면 Authorization 헤더가 Location 으로 따라가는지가
        // 우리가 통제하지 않는 JDK 동작이 된다. 「아마 안 따라갈 것」에 토큰을 걸지 않는다.
        // 3xx 는 isError() 가 false 라 잡지 않으면 정상 응답으로 둔갑한다
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
                        .header(HttpHeaders.LOCATION, BASE_URL + "/repos/o/renamed")
                        .body("{\"message\":\"Moved Permanently\"}"));

        assertThatThrownBy(() -> client.get(GitHubRequest.of("/repos/o/n")))
                .as("「Moved Permanently」 본문이 저장소 메타데이터로 매핑되면 안 된다")
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("리다이렉트");
    }

    @Test
    @DisplayName("프로토콜 헤더를 고정해 보낸다")
    void API_버전과_Accept를_고정한다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/o/n"))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get(GitHubRequest.of("/repos/o/n"));

        server.verify();
    }

    private GitHubApiClient clientWith(GitHubProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        return new GitHubApiClient(builder.build(), StaticTokenCredentials.from(properties),
                properties, new GitHubErrorTranslator(clock), clock);
    }

    private static GitHubProperties properties(String token, int maxRetries, Duration backoff,
            int threshold) {
        return new GitHubProperties(BASE_URL, token, Duration.ofSeconds(1), Duration.ofSeconds(1),
                maxRetries, backoff, threshold);
    }

    private static HttpHeaders rateLimitHeaders(int limit, int remaining, Instant resetAt) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(limit));
        headers.set("X-RateLimit-Remaining", String.valueOf(remaining));
        headers.set("X-RateLimit-Reset", String.valueOf(resetAt.getEpochSecond()));
        return headers;
    }
}
