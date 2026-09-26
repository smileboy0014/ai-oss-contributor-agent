package com.ossagent.support.observability;

/**
 * 규약 문서를 다시 확인한 결과 — 이슈 #68.
 *
 * <p>🔴 <b>「바뀌었는가」와 「판정이 섰는가」를 한 축에 담는다.</b> 둘을 따로 세면
 * 대시보드에서 다시 교차시켜야 하는데, 정작 알고 싶은 것은 그 교차점 하나
 * ({@link #CHANGED_UNVERIFIABLE})다.
 *
 * <p>{@code support} 가 {@code repository.domain} 을 import 하지 않게 하려고 여기에 둔다 —
 * {@link PipelineStage} 와 같은 이유다.
 */
public enum PolicyChangeOutcome {

    /** 비교 기준을 처음 기록했다. 이번에는 비교할 대상이 없었다 */
    BASELINE_RECORDED,

    /** 바뀐 것을 관측하지 못했다 — LLM 을 부르지 않았다 */
    UNCHANGED,

    /** 바뀌었고 다시 판정했다. 금지로 조여졌을 수 있다 */
    CHANGED_REANALYZED,

    /**
     * 🔴 <b>바뀌었는데 판정이 서지 않아 보류로 강등했다</b> — 이 이슈가 닫는 구멍.
     *
     * <p>이 카운터가 0 이 아니면 <b>사람이 봐야 한다.</b> 대상 저장소가 규약을 바꿨는데
     * 우리가 그 문서를 읽지 못한 상태다.
     */
    CHANGED_UNVERIFIABLE,

    /**
     * 이번에는 확인하지 못했다 — 응답 자체를 받지 못했다(5xx · 레이트리밋).
     *
     * <p>⚠️ 「바뀌지 않았다」가 <b>아니다.</b> 지문을 구할 수 없어 원리적으로 알 수 없다.
     * 이 값이 계속 나오면 {@code documents_checked_at} 이 멈춘다 — 그것이 유일한 증거다.
     */
    INDETERMINATE
}
