package com.ossagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.candidate.application.CodingProperties;
import com.ossagent.candidate.adapter.out.llm.LlmCodingAgent;
import com.ossagent.candidate.domain.CodingAgent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 코딩 단계 조립 — {@code agent.coding.*} (#18).
 *
 * <p>{@code ImplementationPlanConfig}(#16)와 같은 모양이다.
 *
 * <p>⚠️ <b>빈 쪽에 {@code @Profile("!fakes")} 를 건다.</b> 빠뜨리면 대역과 실물이 <b>둘 다</b>
 * 올라와 컨텍스트가 기동하지 못한다 — #16 에서 실제로 한 번 그렇게 났다.
 *
 * <p>🔴 노출되는 {@link LanguageModel} 빈은 {@code RecordingLanguageModel} 하나다 —
 * 여기서 주입받는 것도 그것이므로 <b>토큰·비용 기록을 건너뛸 경로가 없다.</b>
 * 그리고 그 데코레이터 아래에 {@code PromptScrubber} 가 있어 <b>스크럽도 강제</b>된다 (S-4).
 */
@Configuration
@EnableConfigurationProperties(CodingProperties.class)
public class CodingConfig {

    @Bean
    @Profile("!fakes")
    public CodingAgent codingAgent(LanguageModel languageModel, CodingProperties properties,
            ObjectMapper objectMapper) {
        return new LlmCodingAgent(languageModel, properties, objectMapper);
    }
}
