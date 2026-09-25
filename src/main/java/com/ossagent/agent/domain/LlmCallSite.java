package com.ossagent.agent.domain;

/**
 * LLM 을 부르는 파이프라인 지점. PRD §6.1 이 정한 4개다.
 *
 * <p>{@code AgentRun.Stage} 와 이름이 겹치지만 <b>일부러 별개 타입</b>이다.
 * {@code AgentRun} 은 {@code candidate} 애그리거트 소유라 {@code agent} 가 직접 import 하면
 * 규율 ④ 위반이다. 매핑은 {@code candidate} 쪽이 한다 —
 * 의존 방향이 {@code candidate → agent} 여야 하기 때문이다.
 *
 * <p>{@code VERIFY} 가 없는 것은 누락이 아니다. 검증은 샌드박스 단계이고 LLM 호출 지점이 아니다.
 */
public enum LlmCallSite {

    /** 이슈의 기여 가능성 판정 */
    ANALYZE,

    /** 구현 계획 수립 */
    PLAN,

    /** 코드 생성 */
    CODE,

    /** 생성된 diff 리뷰 */
    REVIEW,

    /**
     * 대상 저장소의 <b>기여 규약 판정</b> — 이슈 #7.
     *
     * <p>⚠️ 이것만 <b>후보가 없다.</b> 규약 분석은 저장소 단위이고, 후보가 만들어지기 전에
     * 일어난다. 그래서 {@link AgentRunContext#candidateId()} 가 이 지점에서만 {@code null} 이다.
     *
     * <p>「모든 LLM 호출은 후보에 속한다」는 원래 전제가 틀렸다는 뜻이다. 가짜 {@code candidateId}
     * 로 때우면 비용 장부와 MDC 가 오염되므로 전제를 고쳤다.
     *
     * <p>파이프라인 재시도 루프({@code CODE → VERIFY → REVIEW}) 밖이라 {@code attempt} 는
     * 항상 1 이다 — {@code ANALYZE}·{@code PLAN} 과 같은 취급 (Q-6).
     */
    POLICY;

    /** 이 호출 지점이 특정 후보에 속하는가. {@code POLICY} 만 저장소 단위다. */
    public boolean requiresCandidate() {
        return this != POLICY;
    }
}
