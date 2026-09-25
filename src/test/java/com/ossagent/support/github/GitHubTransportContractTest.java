package com.ossagent.support.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.ossagent.config.GitHubClientConfig;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 전송 계약 — <b>실제 소켓을 태워야만 확인되는 것들</b>.
 *
 * <p>Q-9 가 정한 3계층의 세 번째 층이다
 * ({@code .claude/rules/conventions/testing-philosophy.md}). 나머지 둘과 보는 것이 다르다.
 *
 * <ul>
 *   <li>능력 소비자 → 자체 페이크: 계약이 대체 가능한가</li>
 *   <li>어댑터 매핑 → {@code MockRestServiceServer}: 요청 조립·응답 파싱·403 구분</li>
 *   <li><b>전송 계약 → 여기</b>: 읽기 타임아웃·리다이렉트 거부·연결 실패</li>
 * </ul>
 *
 * <p>🔴 <b>왜 {@code MockRestServiceServer} 로는 안 되나.</b> 그것은
 * {@code ClientHttpRequestFactory} 를 통째로 갈아끼운다. JDK {@code HttpClient} 가 아예 돌지
 * 않으므로 {@code read-timeout}·{@code Redirect.NEVER} 는 <b>검증할 수단 자체가 없다</b> —
 * 「프로퍼티에 값이 있다」까지만 확인된다. {@code external-deps.md} 의
 * 「기본값에 맡기면 무한 대기가 생긴다」가 지켜지는지를 알 방법이 그 층에는 없었다.
 *
 * <p>조립은 {@link GitHubClientConfig} 를 <b>그대로 통과</b>시킨다. 테스트가 자기만의
 * {@code RestClient} 를 만들면 검증 대상이 운영 조립이 아니게 된다.
 */
class GitHubTransportContractTest {

    /** 짧게 잡는다 — 타임아웃이 걸리는 것을 초 단위로 기다릴 이유가 없다. */
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server.isRunning()) {
            server.stop();
        }
    }

    /**
     * 재시도는 여기서 보지 않는다({@code GitHubRetryPolicyTest} 가 본다). 0 으로 끄고
     * <b>전송 동작 한 번</b>만 남긴다 — 켜 두면 실패 하나가 3배로 늘어져 느려지기만 한다.
     */
    private GitHubApiClient clientFor(String baseUrl) {
        GitHubProperties properties = new GitHubProperties(baseUrl, token(),
                Duration.ofMillis(500), READ_TIMEOUT, 0, Duration.ofMillis(1), 100);
        Clock clock = Clock.systemUTC();
        GitHubClientConfig config = new GitHubClientConfig();
        return config.gitHubApiClient(properties, config.gitHubCredentials(properties),
                config.gitHubErrorTranslator(clock), clock);
    }

    /**
     * 런타임 조립이다. 리터럴로 두면 소스에 토큰 패턴이 존재하게 되고
     * {@code secret-scan.sh} 가 커밋을 막는다 — S-4.
     */
    private static String token() {
        return "ghp_" + "a".repeat(36);
    }

    @Test
    @DisplayName("읽기 타임아웃이 실제로 걸린다 — 느린 응답을 끝까지 기다리지 않는다")
    void 읽기_타임아웃이_실제로_동작한다() {
        Duration serverDelay = READ_TIMEOUT.multipliedBy(10);
        server.stubFor(get(urlPathEqualTo("/repos/spring-projects/spring-kafka"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay((int) serverDelay.toMillis())
                        .withBody("{}")));
        GitHubApiClient client = clientFor(server.baseUrl());

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.get(
                GitHubRequest.of("/repos/spring-projects/spring-kafka")))
                .as("설정값이 전달되는 것과 실제로 걸리는 것은 다르다. JDK 기본값은 무제한이고, "
                        + "그러면 응답 없는 대외 호출이 스레드를 영원히 잡는다")
                .isInstanceOf(GitHubTransientException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .as("타임아웃이 걸리지 않았다면 서버 지연 %dms 를 전부 기다렸을 것이다 — "
                        + "이 단언이 없으면 「그냥 실패했다」와 구분되지 않는다", serverDelay.toMillis())
                .isLessThan(serverDelay.dividedBy(2));
    }

    @Test
    @DisplayName("리다이렉트를 따라가지 않는다 — Authorization 이 Location 으로 새지 않는다")
    void 리다이렉트를_따라가지_않는다_S4() {
        server.stubFor(get(urlPathEqualTo("/repos/old/name"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", server.baseUrl() + "/repos/new/name")));
        // 따라간다면 이 스텁이 200 을 돌려주고, 호출은 조용히 성공해 버린다
        server.stubFor(get(urlPathEqualTo("/repos/new/name"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        GitHubApiClient client = clientFor(server.baseUrl());

        assertThatThrownBy(() -> client.get(GitHubRequest.of("/repos/old/name")))
                .as("3xx 는 isError() 가 false 다. 잡지 않으면 「Moved Permanently」 본문이 "
                        + "정상 응답으로 둔갑한다")
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("리다이렉트");

        server.verify(0, getRequestedFor(urlPathEqualTo("/repos/new/name")));
    }

    @Test
    @DisplayName("연결 실패는 전송 계층 실패로 번역된다 — 권한 오류와 섞이지 않는다")
    void 연결_실패를_전송_실패로_번역한다() {
        String deadBaseUrl = server.baseUrl();
        server.stop();
        GitHubApiClient client = clientFor(deadBaseUrl);

        assertThatThrownBy(() -> client.get(GitHubRequest.of("/repos/spring-projects/spring-kafka")))
                .as("연결 자체가 안 된 것은 재시도 대상이다. 권한 오류로 분류되면 "
                        + "일시적 네트워크 장애가 영구 실패가 된다")
                .isInstanceOf(GitHubTransientException.class);
    }
}
