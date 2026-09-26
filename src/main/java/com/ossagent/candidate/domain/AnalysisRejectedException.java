package com.ossagent.candidate.domain;

/**
 * LLM 분석 응답이 스키마를 만족하지 못했다 — <b>파싱 실패를 성공으로 처리하지 않는다</b>.
 *
 * <p>🔴 이 예외가 존재하는 이유는 「모델이 뭔가 돌려줬다」와 「쓸 수 있는 판정이 왔다」를
 * 가르기 위해서다. 둘을 뭉개면 {@code difficulty} 가 {@code "보통"} 인 후보,
 * {@code confidence} 가 {@code 1.5} 인 후보가 DB 에 앉는다. 그런 행은 하류에서
 * 조용히 틀린 결정을 만든다.
 *
 * <p>귀결은 {@code ANALYZING → FAILED} 다 (Q-6 — 분석은 파이프라인 재시도 밖).
 * 같은 프롬프트를 다시 보내도 같은 응답이 올 가능성이 높아, 재시도로 풀리는 실패가 아니다.
 *
 * <p>⚠️ 이 예외가 나도 <b>{@code AgentRun} 은 {@code SUCCEEDED}</b> 다. 호출은 성공했고
 * 토큰도 실제로 나갔다 — {@code AgentRun} 은 비용 장부이고 후보 상태는 파이프라인 판정이라
 * 세는 것이 다르다. 여기서 비용을 실패로 적으면 장부가 거짓말을 한다.
 */
public class AnalysisRejectedException extends RuntimeException {

    public AnalysisRejectedException(String message) {
        super(message);
    }
}
