package com.ossagent.agent.domain;

/**
 * LLM 호출 1건을 실행 이력에 붙이기 위한 좌표.
 *
 * <p><b>{@link LanguageModel#complete} 의 필수 인자다.</b> 선택 인자로 두거나 별도 호출로
 * 분리하면 호출자가 기록을 잊어도 컴파일된다 — 그러면 「호출마다 토큰 기록」은 규약일 뿐
 * 구조가 아니게 된다. 비용이 보이지 않는 것이 이 이슈가 없애려는 상태다.
 *
 * <p>세 필드는 로깅 MDC 키와 1:1 로 대응한다 — {@code candidateId} · {@code stage} · {@code attempt}.
 *
 * @param candidateId {@code contribution_candidate.id}. 다른 애그리거트로의 <b>ID 참조</b> — 규율 ④
 * @param callSite    어느 파이프라인 지점의 호출인가
 * @param attempt     파이프라인 재시도 사이클 번호 — 아래
 */
public record AgentRunContext(Long candidateId, LlmCallSite callSite, int attempt) {

    public AgentRunContext {
        if (candidateId == null) {
            throw new IllegalArgumentException("candidateId 는 필수다 — 기록을 붙일 대상이 없다");
        }
        if (callSite == null) {
            throw new IllegalArgumentException("callSite 는 필수다");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 는 1 부터다: " + attempt);
        }
    }

    /**
     * 파이프라인 재시도가 없는 단계용. {@code ANALYZE} · {@code PLAN} 이 여기 해당한다.
     *
     * <p>Q-6 확정(2026-09-25) — 재시도 카운터의 단위는 {@code CODE → VERIFY → REVIEW} 한 바퀴이고,
     * 앞 단계는 그 루프 밖이라 실패가 곧 {@code FAILED} 다. 그래서 {@code attempt} 는 항상 1 이다.
     *
     * <p>⚠️ <b>전송 재시도 횟수를 여기에 더하지 않는다.</b> 두 축은 별개다 — S-6.
     */
    public static AgentRunContext firstAttempt(Long candidateId, LlmCallSite callSite) {
        return new AgentRunContext(candidateId, callSite, 1);
    }
}
