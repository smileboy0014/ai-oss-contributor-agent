package com.ossagent.repository.domain;

/**
 * 파이프라인 단계 — <b>어디서 멈췄는지</b> 사람이 읽을 어휘.
 *
 * <p>🔴 실패 사유에 예외 원문을 싣지 않는 대신 이 어휘와 예외 <b>클래스 이름</b>만 남긴다
 * (S-4). 메시지에는 대상 저장소 텍스트·모델 응답·요청 URL 이 실려 올 수 있다.
 *
 * <p>⚠️ {@code support.observability.PipelineStage} 와 같은 네 값이지만 <b>따로 둔다</b> —
 * 그쪽 javadoc 에 이유가 있다.
 *
 * <p>{@code domain} 에 있는 이유는 {@link ScanPhase} 와 같다 (#26).
 */
public enum ScanStage {
    POLICY,
    SCAN,
    FILTER,
    ANALYZE
}
