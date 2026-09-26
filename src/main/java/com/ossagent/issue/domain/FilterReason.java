package com.ossagent.issue.domain;

/**
 * 왜 그 판정이 났는가 — #9 FR-2.
 *
 * <h2>🔴 자유 텍스트를 쓰지 않는 이유 (S-4)</h2>
 *
 * <p>사유를 {@code String} 으로 받으면 언젠가 이슈 본문 발췌가 들어간다. 그 본문은
 * <b>대상 저장소 사용자가 쓴 임의 텍스트</b>이고, 우리 DB 를 거쳐 나중에 LLM 프롬프트와
 * PR 본문으로 흘러간다 — #7 의 {@code contribution_rules} 가 정확히 그랬다.
 *
 * <p>사유는 「왜 떨어졌나」의 <b>코드</b>이지 증거 인용이 아니다. enum 으로 두면
 * 자유 텍스트가 들어갈 자리가 구조적으로 없다.
 *
 * <p>⚠ 이름을 {@code RejectionReason} 으로 하지 않았다. {@link #SHORT_BODY} 처럼
 * <b>배제가 아닌</b> 사유가 있어서다.
 *
 * <p>⚠ <b>이름이 곧 저장값이다.</b> {@code name()} 이 {@code issue.filter_reason} 에
 * 그대로 들어간다. 상수 이름을 바꾸면 기존 행의 값과 갈라진다.
 */
public enum FilterReason {

    // ── 배제 확정 ───────────────────────────────────────────
    /** 이미 종료된 이슈. ⚠ 수집이 {@code state=open} 고정이라 <b>현재는 발화하지 않는다</b> (#14). */
    CLOSED(FilterOutcome.REJECTED),

    /** {@code breaking-change} 계열 라벨 — 대규모 변경. */
    BREAKING_CHANGE(FilterOutcome.REJECTED),

    /** {@code epic}·{@code rfc}·{@code design} 계열 라벨 — 설계 단위 작업. */
    LARGE_SCOPE(FilterOutcome.REJECTED),

    /** 본문이 비어 있다. 판정할 것이 없다. */
    EMPTY_BODY(FilterOutcome.REJECTED),

    // ── 보류 — 규칙으로 못 가른다 ───────────────────────────
    /**
     * 본문이 짧다.
     *
     * <p>⚠ <b>짧다고 불명확한 것이 아니다.</b> 스택트레이스 링크 한 줄짜리 명확한 버그
     * 리포트가 있다. 확정 배제로 두면 그런 이슈를 전부 잃는다.
     */
    SHORT_BODY(FilterOutcome.UNDECIDED),

    /**
     * 코멘트가 많다.
     *
     * <p>⚠ 논의가 길면 요구가 불명확할 <b>확률</b>이 높지만 인과가 아니다 —
     * 활발한 논의일 수도 있다. 상관을 배제 근거로 쓰지 않는다.
     */
    HEAVY_DISCUSSION(FilterOutcome.UNDECIDED);

    private final FilterOutcome outcome;

    FilterReason(FilterOutcome outcome) {
        this.outcome = outcome;
    }

    public FilterOutcome outcome() {
        return outcome;
    }
}
