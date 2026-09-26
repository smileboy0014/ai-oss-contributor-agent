package com.ossagent.candidate.domain;

import com.ossagent.issue.domain.AnalyzableIssue;

/**
 * 이슈가 기여 가능한지 판정하는 <b>능력</b> — 규율 ③.
 *
 * <p>이름에 기술이 없다. 구현은 {@code candidate/adapter/out/llm/LlmIssueAnalyst} 이고,
 * 그쪽만 {@code LanguageModel} 을 안다. {@code repository} 도메인의
 * {@code ContributionRuleInterpreter} / {@code LlmContributionRuleInterpreter} 와 같은 배치다.
 *
 * <p>🔴 {@code candidateId} 를 받는 이유 — {@code AgentRunContext} 가 {@code ANALYZE} 에
 * {@code candidateId} 를 <b>필수</b>로 요구한다(「기록을 붙일 대상이 없다」).
 * 그래서 이 파이프라인은 <b>후보를 먼저 만들고 분석한다</b>. 순서를 뒤집으면 비용 기록이
 * 붙을 곳이 없어진다 — FR-6.
 */
public interface IssueAnalyst {

    /**
     * 판정한다. <b>관찰값만 돌려준다</b> — {@code REJECTED} 여부는 호출자가 임계로 정한다.
     *
     * @throws AnalysisRejectedException                        응답이 스키마를 만족하지 못했다
     * @throws com.ossagent.agent.domain.LlmException           호출 자체가 실패했다(타임아웃·절단·5xx)
     */
    IssueAnalysis analyze(Long candidateId, AnalyzableIssue issue);
}
