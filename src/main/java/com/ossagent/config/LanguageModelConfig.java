package com.ossagent.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.ossagent.agent.adapter.out.llm.AnthropicLanguageModel;
import com.ossagent.agent.adapter.out.llm.AnthropicProperties;
import com.ossagent.agent.adapter.out.llm.DisabledLanguageModel;
import com.ossagent.agent.adapter.out.llm.LlmRetryPolicy;
import com.ossagent.agent.adapter.out.llm.RecordingLanguageModel;
import com.ossagent.agent.adapter.out.llm.TokenRedactingPromptScrubber;
import com.ossagent.agent.domain.AgentRunRecorder;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.PromptScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 조립. 비즈니스 코드를 두지 않는다.
 *
 * <p>🔴 <b>{@link LanguageModel} 빈은 {@link RecordingLanguageModel} 하나뿐이다.</b>
 * 속 구현({@code AnthropicLanguageModel} · {@code DisabledLanguageModel})을 빈으로 내보내지
 * 않으므로, 소비자가 주입받을 수 있는 것은 <b>항상 기록을 타는 쪽</b>이다.
 * 기록을 건너뛰고 LLM 을 부를 경로가 존재하지 않는다 — 이것이 「호출마다 토큰 기록」을
 * 규약이 아니라 구조로 만드는 지점이다.
 */
@Configuration
@EnableConfigurationProperties(AnthropicProperties.class)
public class LanguageModelConfig {

    private static final Logger log = LoggerFactory.getLogger(LanguageModelConfig.class);

    @Bean
    public PromptScrubber promptScrubber() {
        return new TokenRedactingPromptScrubber();
    }

    /**
     * 🔴 유일하게 노출되는 {@link LanguageModel} 빈.
     *
     * <p>키 유무는 <b>프로퍼티로</b> 판정한다. 환경변수를 직접 읽으면 개발자 머신에
     * {@code ANTHROPIC_API_KEY} 가 export 돼 있을 때 테스트 컨텍스트가 실제 어댑터를 올리고,
     * 테스트 결과가 머신마다 달라진다.
     */
    @Bean
    public LanguageModel languageModel(AnthropicProperties properties, PromptScrubber scrubber,
            AgentRunRecorder recorder) {
        LanguageModel delegate;
        if (properties.hasApiKey()) {
            // 송신 대상 호스트를 우리가 고정한다. fromEnv() 는 ANTHROPIC_BASE_URL 까지 읽어
            // 환경변수 하나로 프롬프트가 다른 곳으로 나갈 수 있다 — S-4
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(properties.apiKey())
                    .baseUrl(properties.baseUrl())
                    .timeout(properties.timeout())
                    // SDK 내장 재시도를 끈다. 맡기면 몇 번 재전송했는지 관측할 수 없고,
                    // 관측 불가능한 재시도가 바로 이 이슈가 없애려는 상태다
                    .maxRetries(0)
                    .build();
            delegate = new AnthropicLanguageModel(client, properties, scrubber,
                    LlmRetryPolicy.from(properties));
            log.info("LLM 어댑터 활성화 endpoint={} model={}", properties.baseUrl(), properties.model());
        } else {
            delegate = new DisabledLanguageModel();
            // safety-ok: 환경변수 이름만 담은 상수 문자열이다. 값 보간이 없고, 애초에 키가 「없는」 경우다
            log.warn("ANTHROPIC_API_KEY 가 비어 있다 — LLM 호출은 실패한다");
        }
        return new RecordingLanguageModel(delegate, recorder);
    }
}
