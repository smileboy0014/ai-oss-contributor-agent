package com.ossagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.candidate.application.IssueAnalysisProperties;
import com.ossagent.candidate.adapter.out.llm.LlmIssueAnalyst;
import com.ossagent.candidate.domain.IssueAnalyst;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 이슈 분석 조립 — #11.
 *
 * <h2>⚠️ 왜 클래스에 {@code @ExternalAdapter} 를 붙이지 않았나</h2>
 *
 * <p>{@code PolicyAnalysisConfig}(#7)는 클래스 전체를 {@code @ExternalAdapter}
 * (= {@code @Profile("!fakes")})로 뺀다. 여기서 그렇게 하면 <b>{@link IssueAnalysisProperties}
 * 까지 함께 사라진다.</b> 그런데 그 설정(배치 크기·상한·신뢰도 임계)은 어댑터가 아니라
 * {@code AnalyzeIssuesUseCase} 가 쓴다 — 대역 프로필에서 UseCase 가 기동하지 못하게 된다.
 *
 * <p>그래서 배제를 <b>빈 메서드로 좁혔다.</b> {@code @ExternalAdapter} 는
 * {@code @Target(TYPE)} 이라 메서드에 못 붙으므로 같은 뜻의 {@code @Profile("!fakes")} 를 쓴다.
 *
 * <h2>{@link LlmIssueAnalyst} 를 대역 프로필에서 빼는 이유는 「대외 차단」이 아니다</h2>
 *
 * <p>이 클래스는 네트워크 클라이언트를 직접 갖지 않는다 — {@link LanguageModel} 능력에만
 * 의존하고, 대외 차단은 그 구현체에 이미 걸려 있다. 빼는 이유는 <b>테스트가 판정을 직접
 * 제어하게 하기 위해서</b>다. JSON 문자열을 조립해 모델 응답을 흉내 내는 것보다
 * {@code FakeIssueAnalyst} 로 결과를 주는 편이 업무 흐름 검증에 맞다 — Q-9 의 「능력 대역」 층.
 *
 * <p>어댑터 자체의 파싱·스키마 검증은 페이크 {@link LanguageModel} 을 끼운 유닛 테스트가 본다.
 */
@Configuration
@EnableConfigurationProperties(IssueAnalysisProperties.class)
public class IssueAnalysisConfig {

    @Bean
    @Profile("!fakes")
    public IssueAnalyst issueAnalyst(LanguageModel languageModel,
            IssueAnalysisProperties properties, ObjectMapper objectMapper) {
        return new LlmIssueAnalyst(languageModel, properties, objectMapper);
    }
}
