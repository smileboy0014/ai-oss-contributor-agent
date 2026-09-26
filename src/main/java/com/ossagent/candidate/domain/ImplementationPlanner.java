package com.ossagent.candidate.domain;

/**
 * 구현 계획을 세우는 <b>능력</b> — 규율 ③. PRD §13 · 이슈 #16.
 *
 * <p>이름에 기술이 없다. 구현은 {@code candidate/adapter/out/llm/LlmImplementationPlanner}
 * 이고 그쪽만 {@code LanguageModel} 을 안다 — {@code IssueAnalyst} / {@code LlmIssueAnalyst}
 * 와 같은 배치다. {@code LanguageModel} 위에 얹히는 <b>2층</b>이다.
 *
 * <h2>🔴 돌려주는 것은 「제안」이지 「진실」이 아니다</h2>
 * 이 능력이 돌려준 계획은 <b>아직 검증되지 않았다.</b> 파일이 실재하는지, 규약을 지키는지,
 * 범위가 맞는지는 {@link PlanValidator} 가 본다. 「생성자를 통과했다 = 실행해도 된다」로
 * 읽으면 이 이슈의 존재 이유가 사라진다.
 */
public interface ImplementationPlanner {

    /**
     * 계획을 하나 세운다.
     *
     * <p>🔴 <b>{@code candidateId} 가 필수다</b> — {@code AgentRunContext} 가 {@code PLAN} 에
     * 후보를 요구한다. 비용 기록을 붙일 대상이 없으면 호출이 장부에서 사라진다 (FR-7).
     *
     * <p>⚠️ {@code input.feedback()} 이 있으면 <b>직전 거부 사유를 프롬프트에 싣는다.</b>
     * 싣지 않으면 재생성이 같은 실수를 반복하고 예산만 태운다 — 구현의 의무다.
     *
     * @param candidateId 비용·이력을 붙일 후보
     * @param input       이슈 · 저장소 컨텍스트 · 규약 · (재생성이면) 거부 사유
     * @throws PlanRejectedException                  응답이 스키마를 만족하지 못했다
     * @throws com.ossagent.agent.domain.LlmException 호출 자체가 실패했다 (타임아웃·절단·5xx)
     */
    ImplementationPlan plan(Long candidateId, PlanningInput input);
}
