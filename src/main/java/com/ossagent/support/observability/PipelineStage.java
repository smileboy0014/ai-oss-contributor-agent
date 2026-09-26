package com.ossagent.support.observability;

/**
 * 스캔 파이프라인 단계 — 계측용 어휘.
 *
 * <p>⚠️ {@code ScanExecutionState.Stage}(#14) 와 같은 네 값이지만 <b>따로 둔다.</b>
 * 그쪽은 {@code repository.application} 에 있고, {@code support} 가 남의 application 을
 * import 하면 의존 방향이 어긋난다({@code support} 는 도메인 없는 공통이다).
 * 도메인 <b>값 타입</b>을 import 하는 것({@code LlmCallSite} 등)과는 사정이 다르다.
 *
 * <p>매핑은 호출부({@code ScanPipelineUseCase})가 한다 — 한 줄이고, 그 대가로
 * {@code support} 가 어느 도메인의 내부 구조에도 묶이지 않는다.
 */
public enum PipelineStage {
    POLICY,
    SCAN,
    FILTER,
    ANALYZE
}
