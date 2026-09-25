package com.ossagent.agent.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import com.anthropic.errors.AnthropicIoException;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmPermanentException;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmResponse;
import com.ossagent.agent.domain.LlmTransientException;
import com.ossagent.support.secret.TokenRedactor;
import java.time.Duration;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 어댑터 매핑 층 — 소켓 없이 요청 조립·응답 파싱·오류 번역을 본다.
 *
 * <p>읽기 타임아웃·연결 실패가 <b>실제로 걸리는지</b>는 여기서 알 수 없다. 그것은 전송 계약
 * 층(WireMock)의 몫이다 — 두 층이 서로 다른 것을 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AnthropicLanguageModelTest {

    private static final AgentRunContext CTX =
            AgentRunContext.firstAttempt(1L, LlmCallSite.ANALYZE);

    private static String messageJson(String text, String stopReason, int in, int out) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5",
                 "content":[{"type":"text","text":"%s"}],
                 "stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":%d,"output_tokens":%d}}
                """.formatted(text, stopReason, in, out);
    }

    private static AnthropicLanguageModel modelWith(StubHttpClient http, AnthropicProperties props) {
        AnthropicClient client = new AnthropicClientImpl(ClientOptions.builder()
                .putHeader("x-api-key", "test-key-not-a-real-secret")
                .baseUrl(props.baseUrl())
                .httpClient(http)
                // SDK 내장 재시도를 끈다 — 재시도는 우리 루프가 돈다
                .maxRetries(0)
                .build());
        return new AnthropicLanguageModel(client, props, new TokenRedactingPromptScrubber(),
                LlmRetryPolicy.from(props));
    }

    private static AnthropicProperties props(int maxRetries) {
        return new AnthropicProperties(null, "test-key-not-a-real-secret", null, 16000,
                Duration.ofSeconds(5), maxRetries, Duration.ofMillis(1));
    }

    @Test
    void 응답_텍스트와_토큰을_우리_값으로_옮긴다() {
        var http = new StubHttpClient().respondJson(messageJson("안녕", "end_turn", 11, 7));

        LlmResponse response = modelWith(http, props(0))
                .complete(CTX, new LlmRequest(null, "질문", 100));

        assertThat(response.text()).isEqualTo("안녕");
        assertThat(response.usage().inputTokens()).isEqualTo(11);
        assertThat(response.usage().outputTokens()).isEqualTo(7);
    }

    @Test
    void 프롬프트에_토큰_패턴이_있으면_송신되지_않는다_S4() {
        var http = new StubHttpClient().respondJson(messageJson("ok", "end_turn", 1, 1));
        // 진짜 토큰이 아니라 패턴만 맞춘 가짜다 — 픽스처에 실제 시크릿을 넣지 않는다
        String leaked = "ghp_" + "A".repeat(36);

        modelWith(http, props(0)).complete(CTX,
                new LlmRequest("시스템 " + leaked, "본문 " + leaked, 100));

        String sent = http.sentBodies().get(0);
        assertThat(sent)
                .as("스크럽되지 않은 토큰이 모델 제공자로 나갔다 — S-4 본류 위반")
                .doesNotContain(leaked);
        assertThat(sent)
                .as("system 과 userPrompt 양쪽 모두 치환돼야 한다")
                .contains(TokenRedactor.MASK);
    }

    @Test
    void 상한에서_잘린_응답은_성공으로_반환되지_않는다() {
        var http = new StubHttpClient().respondJson(messageJson("잘린", "max_tokens", 9, 16000));

        assertThatThrownBy(() -> modelWith(http, props(0))
                .complete(CTX, new LlmRequest(null, "질문", 16000)))
                .as("잘린 응답을 성공으로 돌려주면 소비자가 잘린 JSON 을 진실로 파싱한다")
                .isInstanceOf(LlmPermanentException.class)
                .extracting(e -> ((LlmPermanentException) e).reason())
                .isEqualTo(LlmFailureReason.TRUNCATED);
    }

    @Test
    void 절단_예외는_실제_소비_토큰을_싣는다() {
        var http = new StubHttpClient().respondJson(messageJson("잘린", "max_tokens", 9, 16000));

        assertThatThrownBy(() -> modelWith(http, props(0))
                .complete(CTX, new LlmRequest(null, "질문", 16000)))
                .isInstanceOfSatisfying(LlmPermanentException.class, e ->
                        assertThat(e.usage())
                                .as("호출자가 상한을 얼마나 올려야 할지 판단할 유일한 근거다")
                                .hasValueSatisfying(u ->
                                        assertThat(u.outputTokens()).isEqualTo(16000)));
    }

    @Test
    void 절단된_응답은_같은_요청으로_재전송하지_않는다_S6() {
        // 재시도 예산이 2 여도 절단은 재전송 대상이 아니다
        var http = new StubHttpClient()
                .respondJson(messageJson("잘린", "max_tokens", 9, 100))
                .respondJson(messageJson("잘린", "max_tokens", 9, 100))
                .respondJson(messageJson("잘린", "max_tokens", 9, 100));

        assertThatThrownBy(() -> modelWith(http, props(2))
                .complete(CTX, new LlmRequest(null, "질문", 100)))
                .isInstanceOf(LlmPermanentException.class);

        assertThat(http.sentCount())
                .as("같은 상한으로 재전송하면 같은 지점에서 잘린다 — 입력 토큰만 배로 태운다")
                .isEqualTo(1);
    }

    @Test
    void 거부된_응답은_재시도하지_않는다() {
        var http = new StubHttpClient()
                .respondJson(messageJson("", "refusal", 5, 0))
                .respondJson(messageJson("", "refusal", 5, 0));

        assertThatThrownBy(() -> modelWith(http, props(2))
                .complete(CTX, new LlmRequest(null, "질문", 100)))
                .isInstanceOfSatisfying(LlmPermanentException.class, e ->
                        assertThat(e.reason()).isEqualTo(LlmFailureReason.REJECTED));

        assertThat(http.sentCount()).isEqualTo(1);
    }

    @Test
    void 권한_오류는_재시도하지_않는다() {
        var http = new StubHttpClient().respondStatus(403).respondStatus(403);

        assertThatThrownBy(() -> modelWith(http, props(2))
                .complete(CTX, new LlmRequest(null, "질문", 100)))
                .isInstanceOf(LlmPermanentException.class);

        assertThat(http.sentCount())
                .as("403 을 재시도하면 무한 루프가 된다")
                .isEqualTo(1);
    }

    @Test
    void 레이트리밋은_상한까지만_재시도한다_S6() {
        var http = new StubHttpClient()
                .respondStatus(429)
                .respondStatus(429)
                .respondStatus(429)
                .respondStatus(429);

        assertThatThrownBy(() -> modelWith(http, props(2))
                .complete(CTX, new LlmRequest(null, "질문", 100)))
                .isInstanceOfSatisfying(LlmTransientException.class, e ->
                        assertThat(e.reason()).isEqualTo(LlmFailureReason.RATE_LIMITED));

        assertThat(http.sentCount())
                .as("첫 시도 + 재시도 2 = 3. 상한을 넘겨 도는 경로가 있으면 안 된다")
                .isEqualTo(3);
    }

    @Test
    void 일시_장애_후_성공하면_결과를_돌려준다() {
        var http = new StubHttpClient()
                .respondStatus(503)
                .respondJson(messageJson("복구", "end_turn", 3, 4));

        LlmResponse response = modelWith(http, props(2))
                .complete(CTX, new LlmRequest(null, "질문", 100));

        assertThat(response.text()).isEqualTo("복구");
        assertThat(http.sentCount()).isEqualTo(2);
    }

    @Test
    void 예외에_키와_엔드포인트가_섞이지_않는다_S4() {
        String key = "sk-ant-" + "B".repeat(40);
        var http = new StubHttpClient().failWith(
                new AnthropicIoException("https://api.anthropic.com/v1/messages?key=" + key));

        assertThatThrownBy(() -> modelWith(http, props(0))
                .complete(CTX, new LlmRequest(null, "질문", 100)))
                .isInstanceOf(LlmTransientException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .as("SDK 예외 원문을 그대로 옮기면 요청 URL 과 토큰이 로그·DB 로 나간다")
                        .doesNotContain(key)
                        .doesNotContain("api.anthropic.com"));
    }

    @Test
    void 스크럽기_없이는_어댑터를_만들_수_없다_S4() {
        AnthropicClient client = new AnthropicClientImpl(ClientOptions.builder()
                .putHeader("x-api-key", "test-key-not-a-real-secret")
                .httpClient(new StubHttpClient())
                .build());

        assertThatThrownBy(() -> new AnthropicLanguageModel(
                client, props(0), null, LlmRetryPolicy.from(props(0))))
                .as("null 을 허용하면 S-4 본류 방어가 조용히 0 이 된다")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
