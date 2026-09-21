package com.ossagent.candidate.domain;

/**
 * 기여 후보의 상태. 전이 규칙은 {@code .claude/codemaps/domain.md} 의 상태머신이 정본이다.
 *
 * <p>종단 상태는 {@link #PR_CREATED} · {@link #REJECTED} · {@link #FAILED} 셋이며,
 * 종단에서 나가는 전이는 없다.
 */
public enum CandidateStatus {
    DISCOVERED,
    ANALYZING,
    ANALYZED,
    SELECTED,
    IMPLEMENTING,
    TESTING,
    REVIEWING,
    READY_FOR_PR,
    PR_CREATED,
    REJECTED,
    FAILED
}
