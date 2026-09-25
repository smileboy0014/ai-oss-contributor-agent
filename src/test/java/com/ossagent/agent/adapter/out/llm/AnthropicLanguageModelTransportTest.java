package com.ossagent.agent.adapter.out.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmException;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmRequest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 전송 계약 층 — Q-9 이 정한 3계층의 맨 아래. <b>소켓이 실제로 있어야 알 수 있는 것</b>만 본다.
 *
 * <p>🔴 왜 이 층이 따로 필요한가 — 스텁 {@code HttpClient}(중간 층)를 꽂으면 <b>OkHttp 가 아예
 * 돌지 않는다.</b> {@code timeout} 설정이 「프로퍼티에 값이 있다」까지만 확인되고 실제로 걸리는지는
 * 알 수 없는 상태가 된다. {@code external-deps.md} 의 「기본값에 맡기면 무한 대기가 생긴다」가
 * 거기서 무력화된다.
 *
 * <p>여기서만 실제 클라이언트({@code AnthropicOkHttpClient})를 쓴다. 느리므로 <b>소켓이 필요한
 * 것만</b> 둔다 — 요청 조립·응답 파싱·오류 번역은 중간 층이 훨씬 빠르게 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AnthropicLanguageModelTransportTest {

    private static final AgentRunContext CTX =
            AgentRunContext.firstAttempt(1L, LlmCallSite.ANALYZE);

    private WireMockServer wireMock;

    @BeforeEach
    void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopServer() {
        if (wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    private AnthropicLanguageModel modelWith(AnthropicProperties props) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey("test-key-not-a-real-secret")
                .baseUrl(props.baseUrl())
                .timeout(props.timeout())
                .maxRetries(0)
                .build();
        return new AnthropicLanguageModel(client, props, new TokenRedactingPromptScrubber(),
                LlmRetryPolicy.from(props));
    }

    private AnthropicProperties props(Duration timeout, int maxRetries) {
        return new AnthropicProperties("http://localhost:" + wireMock.port(),
                "test-key-not-a-real-secret", null, 100, timeout, maxRetries, Duration.ofMillis(1));
    }

    @Test
    void 읽기_타임아웃이_실제로_걸린다() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withFixedDelay(3000)
                        .withStatus(200)
                        .withBody("{}")));

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> modelWith(props(Duration.ofMillis(300), 0))
                .complete(CTX, new LlmRequest(null, "질문", 100)))
                .as("타임아웃이 안 걸리면 호출 하나가 스레드를 무한정 잡는다")
                .isInstanceOf(LlmException.class);

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertThat(elapsedMs)
                .as("설정값이 실제로 적용되지 않으면 3초를 다 기다린다")
                .isLessThan(2500);
    }

    @Test
    void 연결_실패는_재시도_대상이다() {
        // 클라이언트를 먼저 만들고(포트가 살아 있을 때) 서버를 내려 연결 자체를 실패시킨다
        var model = modelWith(props(Duration.ofSeconds(2), 1));
        wireMock.stop();

        assertThatThrownBy(() -> model.complete(CTX, new LlmRequest(null, "질문", 100)))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable())
                            .as("연결 실패는 다음에 성공할 수 있다 — 재전송 대상이다")
                            .isTrue();
                    assertThat(e.reason()).isEqualTo(LlmFailureReason.TIMEOUT);
                });
    }

    @Test
    void 정상_응답은_소켓_위에서도_같게_파싱된다() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"msg_1","type":"message","role":"assistant",
                                 "model":"claude-sonnet-5",
                                 "content":[{"type":"text","text":"소켓"}],
                                 "stop_reason":"end_turn","stop_sequence":null,
                                 "usage":{"input_tokens":2,"output_tokens":3}}
                                """)));

        var response = modelWith(props(Duration.ofSeconds(5), 0))
                .complete(CTX, new LlmRequest(null, "질문", 100));

        assertThat(response.text()).isEqualTo("소켓");
        assertThat(response.usage().outputTokens()).isEqualTo(3);
    }
}
