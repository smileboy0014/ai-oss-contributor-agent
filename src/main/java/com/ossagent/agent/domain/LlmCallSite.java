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
    REVIEW
}
