package com.ossagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.candidate.adapter.out.llm.LlmImplementationPlanner;
import com.ossagent.candidate.application.ImplementationPlanProperties;
import com.ossagent.candidate.domain.ImplementationPlanner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 구현 계획 조립 — 이슈 #16.
 *
 * <h2>⚠️ 클래스가 아니라 <b>빈 메서드</b>를 대역 프로필에서 뺀다</h2>
 * 클래스 전체에 {@code @ExternalAdapter}(= {@code @Profile("!fakes")})를 붙이면
 * {@link ImplementationPlanProperties} 빈까지 함께 빠진다. 그 값은
 * {@code PlanImplementationUseCase} 가 쓰므로 <b>대역 프로필에서 UseCase 가 기동하지 못한다.</b>
 * {@code IssueAnalysisConfig}(#11)가 같은 이유로 같은 모양을 택했다.
 *
 * <p>{@code @ExternalAdapter} 는 {@code @Target(TYPE)} 이라 메서드에 못 붙는다 —
 * 같은 뜻의 {@code @Profile("!fakes")} 를 쓴다.
 *
 * <h2>빼는 이유는 「대외 차단」이 아니다</h2>
 * {@link LlmImplementationPlanner} 는 네트워크 클라이언트를 직접 갖지 않는다 —
 * {@link LanguageModel} <b>능력</b>에만 기댄다. 빼는 이유는 <b>업무 흐름 테스트에서
 * {@code FakeImplementationPlanner} 로 계획을 직접 주는 편이 맞기</b> 때문이다
 * (Q-9 의 「능력 대역」 층). 프롬프트 조립·파싱은 페이크 {@link LanguageModel} 을 끼운
 * 유닛 테스트가 본다.
 *
 * <p>⚠️ 이 {@code @Profile} 이 빠지면 대역과 실물이 <b>둘 다</b> 올라와 컨텍스트가 기동하지
 * 못한다. 실제로 한 번 그렇게 났다.
 */
@Configuration
@EnableConfigurationProperties(ImplementationPlanProperties.class)
public class ImplementationPlanConfig {

    /**
     * 🔴 노출되는 {@link LanguageModel} 빈은 {@code RecordingLanguageModel} 하나다 —
     * 여기서 주입받는 것도 그것이므로 <b>기록을 건너뛸 경로가 없다</b> (FR-7).
     */
    @Bean
    @Profile("!fakes")
    public ImplementationPlanner implementationPlanner(LanguageModel languageModel,
            ImplementationPlanProperties properties, ObjectMapper objectMapper) {
        return new LlmImplementationPlanner(languageModel, properties, objectMapper);
    }
}
