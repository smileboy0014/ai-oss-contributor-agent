package com.ossagent.issue.domain;

/**
 * 규칙 필터의 판정 — #9.
 *
 * <h2>🔴 3상태인 이유</h2>
 *
 * <p>2상태(통과/배제)로 두면 <b>규칙으로 가를 수 없는 것을 통과로 뭉개게 된다.</b>
 * 이 단계의 목적은 「LLM 에 넣기 전에 싸게 거른다」이지 「전부 판정한다」가 아니다.
 * 규칙이 확정할 수 없는 신호(본문이 짧다 · 논의가 길다)는 <b>배제도 통과도 아니고</b>,
 * 그 사실 자체가 하류(#11)에 전달돼야 할 정보다.
 *
 * <p>⚠ <b>선언 순서가 곧 집계 우선순위다.</b> 규칙 여러 개가 서로 다른 판정을 내면
 * 위에 선언된 것이 이긴다 — {@link #or(FilterOutcome)}. 순서를 바꾸면 판정이 바뀐다.
 */
public enum FilterOutcome {

    /** 배제 확정. 후보가 되지 않는다. */
    REJECTED,

    /**
     * 규칙으로 가를 수 없다 — <b>LLM 이 본다</b>(#11).
     *
     * <p>배제가 아니므로 후보 생성 대상이다. 「판정하지 않았다」를 「통과」로 적지 않기
     * 위해 존재한다.
     */
    UNDECIDED,

    /** 어떤 규칙에도 걸리지 않았다. */
    PASSED;

    /**
     * 둘 중 더 강한 판정을 고른다 — {@code REJECTED} &gt; {@code UNDECIDED} &gt; {@code PASSED}.
     *
     * <p>배제가 하나라도 있으면 배제이고, 없더라도 보류가 있으면 보류다.
     */
    public FilterOutcome or(FilterOutcome other) {
        return compareTo(other) <= 0 ? this : other;
    }

    /** 후보에서 빠지는가. {@code UNDECIDED} 는 <b>빠지지 않는다</b>. */
    public boolean excluded() {
        return this == REJECTED;
    }
}
