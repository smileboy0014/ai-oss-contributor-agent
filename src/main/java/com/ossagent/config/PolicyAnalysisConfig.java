package com.ossagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.repository.adapter.out.github.GitHubPolicyDocumentSource;
import com.ossagent.repository.adapter.out.llm.LlmContributionRuleInterpreter;
import com.ossagent.repository.adapter.out.llm.PolicyAnalysisProperties;
import com.ossagent.repository.domain.ContributionRuleInterpreter;
import com.ossagent.repository.domain.PolicyDocumentSource;
import com.ossagent.repository.domain.RepositorySource;
import com.ossagent.support.ExternalAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 규약 분석 조립. 비즈니스 코드를 두지 않는다.
 *
 * <p>🔴 {@code @ExternalAdapter} — 여기서 만드는 빈들이 실제 GitHub·LLM 을 탄다.
 * {@code fakes} 프로파일에서 통째로 빠지고 {@code @FakeAdapter} 대역이 대신 뜬다.
 * 클래스가 아니라 {@code @Configuration} 에 붙는 경우이고, {@code ExternalAdapter} javadoc 이
 * 그 용법을 명시한다.
 */
@Configuration
@ExternalAdapter
@EnableConfigurationProperties(PolicyAnalysisProperties.class)
public class PolicyAnalysisConfig {

    @Bean
    public PolicyDocumentSource policyDocumentSource(RepositorySource repositorySource,
            PolicyAnalysisProperties properties) {
        return new GitHubPolicyDocumentSource(repositorySource, properties.maxDocumentChars());
    }

    @Bean
    public ContributionRuleInterpreter contributionRuleInterpreter(LanguageModel languageModel,
            PolicyAnalysisProperties properties, ObjectMapper objectMapper) {
        return new LlmContributionRuleInterpreter(languageModel, properties, objectMapper);
    }
}
