package com.ossagent.support.observability;

/**
 * 게이트 판정.
 *
 * <p>🔴 <b>{@code PASSED} 가 있는 것이 핵심이다.</b> {@code logging.md} 가
 * 「안전 게이트 … <b>통과한 것도 남긴다.</b> 사고 후 「막았는가」를 증명할 수 있어야 한다」고
 * 요구한다. 차단만 세면 분모가 없어 「막힌 비율」을 계산할 수 없고,
 * <b>「0건 차단」과 「계측 고장」이 구분되지 않는다.</b>
 */
public enum GateOutcome {
    PASSED,
    BLOCKED
}
