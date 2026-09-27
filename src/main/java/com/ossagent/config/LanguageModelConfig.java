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
import com.ossagent.agent.domain.LlmPricing;
import com.ossagent.agent.domain.PromptScrubber;
import com.ossagent.support.ExternalAdapter;
import com.ossagent.support.observability.MetricNames;
import com.ossagent.support.observability.PipelineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 조립. 비즈니스 코드를 두지 않는다.
 *
 * <p>🔴 <b>{@code @ExternalAdapter} 를 클래스에 붙였다.</b> 이 조립이 실제 네트워크를 타는
 * 클라이언트를 만들기 때문이다 — {@code fakes} 프로필에서 통째로 빠지고, 대신
 * {@code FakeLanguageModel}({@code @FakeAdapter})이 뜬다. 어댑터가 아니라 {@code @Configuration}
 * 에 붙는 것이 맞다 — {@code ExternalAdapter} javadoc 이 그 경우를 명시한다.
 *
 * <p>🔴 <b>{@link LanguageModel} 빈은 {@link RecordingLanguageModel} 하나뿐이다.</b>
 * 속 구현({@code AnthropicLanguageModel} · {@code DisabledLanguageModel})을 빈으로 내보내지
 * 않으므로, 소비자가 주입받을 수 있는 것은 <b>항상 기록을 타는 쪽</b>이다.
 * 기록을 건너뛰고 LLM 을 부를 경로가 존재하지 않는다 — 이것이 「호출마다 토큰 기록」을
 * 규약이 아니라 구조로 만드는 지점이다.
 */
@Configuration
@ExternalAdapter
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
            AgentRunRecorder recorder, PipelineMetrics metrics) {
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
        LlmPricing pricing = properties.pricingForModel().orElse(null);
        logPricing(properties, pricing);
        return new RecordingLanguageModel(delegate, recorder, metrics, pricing);
    }

    /**
     * 🔴 <b>단가 오설정을 알아차릴 유일한 수단</b> — 이슈 #71.
     *
     * <p>단가가 틀렸을 때의 증상이 「비용이 조용히 안 찍힌다」라서, 계측을 보고 있어도
     * 알아차리지 못한다. 그래서 기동 시 <b>모델과 단가를 함께</b> 한 번 남긴다 —
     * 표는 있는데 키가 어긋난 경우를 가르려고 <b>설정된 키 목록</b>까지 찍는다.
     *
     * <p>⚠️ 찍는 값은 단가(숫자)와 모델 ID(우리 설정 문자열)뿐이다. 키·프롬프트·응답이
     * 섞일 자리가 없다 — S-4.
     */
    private static void logPricing(AnthropicProperties properties, LlmPricing pricing) {
        if (pricing == null) {
            log.warn("LLM 단가가 없다 model={} 단가표={} — {} 를 만들지 않는다. "
                            + "토큰은 그대로 세므로 나중에 곱할 수 있다",
                    properties.model(), properties.pricing().keySet(), MetricNames.LLM_COST);
            return;
        }
        // ⚠ 메시지에 영어 token 을 쓰지 않는다 — safety-boundary-check.sh 의 S-4 패턴에
        //   걸린다. 여기 찍는 것은 단가(숫자)와 모델 ID(우리 설정)뿐이라 오탐이지만,
        //   예외 표시를 남기느니 문구를 고치는 쪽이 맞다 (게이트를 넓히지 않는다)
        log.info("LLM 단가 model={} input={} output={} ({} / 100만 토큰)",
                properties.model(), pricing.input(), pricing.output(), MetricNames.CURRENCY);
    }
}
