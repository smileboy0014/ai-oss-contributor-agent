package com.ossagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.candidate.adapter.out.llm.LlmDiffReviewer;
import com.ossagent.candidate.application.DiffReviewProperties;
import com.ossagent.candidate.domain.DiffReviewer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * diff 리뷰 조립 — 이슈 #20. {@code IssueAnalysisConfig} 와 같은 모양이다.
 *
 * <p>⚠️ {@code @ExternalAdapter} 를 <b>클래스에 붙이지 않는다.</b> 메서드 단위
 * {@code @Profile("!fakes")} 로 충분하고, 클래스에 붙이면
 * {@code @EnableConfigurationProperties} 까지 대역 컨텍스트에서 빠져 설정 바인딩이 사라진다.
 */
@Configuration
@EnableConfigurationProperties(DiffReviewProperties.class)
public class DiffReviewConfig {

    @Bean
    @Profile("!fakes")
    public DiffReviewer diffReviewer(LanguageModel languageModel,
            DiffReviewProperties properties, ObjectMapper objectMapper) {
        return new LlmDiffReviewer(languageModel, properties, objectMapper);
    }
}
