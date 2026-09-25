package com.ossagent.agent.domain;

/**
 * LLM 호출 1건의 실행 이력을 남기는 능력. <b>비용 장부</b>다.
 *
 * <h2>왜 {@code agent} 가 선언하고 남이 구현하나</h2>
 *
 * <p>실행 이력 엔티티 {@code AgentRun} 은 {@code candidate} 애그리거트 소유다. 「에이전트가
 * 만들어낸다」는 사실이 소유를 정하지 않고, <b>후보 상태 전이와 같은 트랜잭션에서 일관성을
 * 지켜야 하는 것</b>이 소유를 정한다 (불변식 ⑧).
 *
 * <p>그래서 {@code agent} 가 {@code AgentRun} 을 직접 import 하면 규율 ④ 위반이다.
 * 여기서는 <b>능력만 선언</b>하고, 구현은 {@code candidate} 쪽 UseCase 가 맡는다 —
 * 의존 방향이 {@code candidate → agent} 가 되어 코드맵의 단방향 참조와 맞는다.
 *
 * <h2>왜 시작과 끝을 나누나</h2>
 *
 * <p>호출 전에 행을 만들고 호출 후에 채운다. 한 트랜잭션으로 감싸면 <b>대외 호출이 트랜잭션
 * 안에 들어가</b> 커넥션을 붙잡는다 — 이 프로젝트의 🔴 규율이다.
 *
 * <p>더 중요한 이유 — <b>실패한 호출도 토큰을 먹는다.</b> 타임아웃으로 끊긴 호출은 사용량을
 * 돌려받지 못하지만 모델은 이미 생성했고 과금된다. 시작 행을 먼저 남기지 않으면 그 비용이
 * 장부에서 통째로 사라지고, 「재시도 루프가 조용히 돈을 태운다」가 그대로 재현된다.
 */
public interface AgentRunRecorder {

    /**
     * 호출 시작을 기록한다. <b>대외 호출 전에</b> 짧은 트랜잭션으로 끝낸다.
     *
     * @return 이후 {@link #succeeded}·{@link #failed} 가 가리킬 실행 기록 식별자
     */
    Long started(AgentRunContext ctx);

    /** 성공과 토큰을 기록한다. */
    void succeeded(Long runId, LlmUsage usage);

    /**
     * 실패와 <b>아는 만큼의</b> 토큰을 기록한다.
     *
     * <p>⚠️ {@code reason} 은 <b>우리 어휘</b>다. SDK 예외 메시지를 넘기지 않는다 —
     * {@code AgentRun.errorMessage} 에 토큰이 섞이는 것이 실제로 잦다 (S-4).
     *
     * @param usage 알 수 있으면 실제 사용량, 모르면 {@code null}.
     *              절단은 <b>응답을 받았으므로 사용량을 안다</b> — 실패지만 토큰은 나갔다.
     *              이것을 성공으로 기록하면 장부가 거짓말을 하고, 사용량을 버리면 비용이 사라진다.
     *              타임아웃은 응답이 없어 {@code null} 이지만 <b>비용이 0 이라는 뜻은 아니다</b>
     */
    void failed(Long runId, LlmFailureReason reason, LlmUsage usage);
}
