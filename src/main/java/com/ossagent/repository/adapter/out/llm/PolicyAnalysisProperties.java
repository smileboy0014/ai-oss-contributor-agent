package com.ossagent.repository.adapter.out.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 규약 분석 설정.
 *
 * <p>접두사가 {@code agent.llm} 이 아니라 {@code repository.policy} 인 이유 — 이 값들은
 * LLM 전송 설정이 아니라 <b>규약 분석이라는 업무</b>의 파라미터다. 소유가 다르면 키도 다르다.
 *
 * @param maxDocumentChars 문서 하나의 상한. 넘으면 <b>자르지 않고 보류</b>한다 —
 *                         잘린 뒷부분에 금지 문구가 있었는지 판정할 방법이 없다 (S-5).
 *                         {@code GitHubRepositorySource} 가 1MB 초과를 이미 거르므로
 *                         이 상한은 그 아래 구간에 작동한다. README 를 넣으면 실제로 걸린다
 * @param maxOutputTokens  판정 응답의 출력 상한. 판정은 짧은 JSON 이라 크게 잡을 이유가 없다
 */
@ConfigurationProperties("repository.policy")
public record PolicyAnalysisProperties(int maxDocumentChars, int maxOutputTokens) {

    private static final int DEFAULT_MAX_DOCUMENT_CHARS = 40_000;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 2_000;

    public PolicyAnalysisProperties {
        maxDocumentChars = maxDocumentChars <= 0 ? DEFAULT_MAX_DOCUMENT_CHARS : maxDocumentChars;
        maxOutputTokens = maxOutputTokens <= 0 ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens;
    }

    public static PolicyAnalysisProperties defaults() {
        return new PolicyAnalysisProperties(DEFAULT_MAX_DOCUMENT_CHARS, DEFAULT_MAX_OUTPUT_TOKENS);
    }
}
