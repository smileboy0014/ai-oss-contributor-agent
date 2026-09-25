package com.ossagent.candidate.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 기여 후보의 상태. 전이 규칙은 {@code .claude/codemaps/domain.md} 의 상태머신이 정본이다.
 *
 * <p><b>어디서 어디로 갈 수 있는가를 이 enum 이 소유한다.</b> 바깥 {@code Map} 으로 두면
 * 상태를 추가할 때 표를 같이 고쳐야 한다는 사실이 컴파일러에 보이지 않는다.
 *
 * <p>🔴 <b>종단 판정을 하드코딩 목록으로 하지 않는다.</b> {@link #isTerminal()} 은
 * 「허용 집합이 비었는가」로 정의한다. 목록을 따로 두면 전이를 추가하면서 갱신을 잊는 순간
 * <b>종단에서 나가는 길이 조용히 열린다</b> — 불변식 ①이 무너지는 가장 현실적인 경로다.
 *
 * <p>⚠️ {@code @Enumerated(STRING)} 으로 영속된다. <b>선언 순서를 바꿔도 안전하지만</b>
 * 이름을 바꾸면 기존 행을 읽지 못한다.
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
    FAILED;

    private Set<CandidateStatus> allowedNext;

    static {
        DISCOVERED.allowedNext = setOf(ANALYZING);

        // 분석 실패는 즉시 FAILED 다 — Q-6 확정. 이 전이가 없으면 ANALYZING 에서 나가는 길이
        // ANALYZED 하나뿐이라, LLM 분석이 실패한 후보가 영구히 박힌다.
        // 재분석도 FAILED 도 불가능해 「사람에게 넘기는 신호」가 발생하지 않는다 (S-6)
        ANALYZING.allowedNext = setOf(ANALYZED, FAILED);

        ANALYZED.allowedNext = setOf(SELECTED, REJECTED);

        // SELECTED → REJECTED 는 사람의 선택 취소다 — Q-5 확정 ②.
        // SELECTED 는 종단이 아니므로 「종단에서 나가는 전이 금지」에 걸리지 않는다
        SELECTED.allowedNext = setOf(IMPLEMENTING, REJECTED);

        IMPLEMENTING.allowedNext = setOf(TESTING, FAILED);

        // TESTING·REVIEWING → IMPLEMENTING 이 「구현→검증→리뷰」 루프다.
        // 이 되돌아감 한 번이 attempt 1 을 태운다 — Q-6
        TESTING.allowedNext = setOf(REVIEWING, IMPLEMENTING, FAILED);
        REVIEWING.allowedNext = setOf(READY_FOR_PR, IMPLEMENTING, FAILED);

        READY_FOR_PR.allowedNext = setOf(PR_CREATED);

        // ── 종단 3개. 비어 있다는 것이 곧 종단이라는 뜻이다 — 불변식 ① · S-6 ──
        PR_CREATED.allowedNext = Set.of();
        REJECTED.allowedNext = Set.of();
        FAILED.allowedNext = Set.of();
    }

    private static Set<CandidateStatus> setOf(CandidateStatus first, CandidateStatus... rest) {
        return Collections.unmodifiableSet(EnumSet.of(first, rest));
    }

    /**
     * 여기서 나가는 전이가 하나도 없는가.
     *
     * <p>종단 목록을 따로 들지 않는다. 전이 표가 곧 종단 정의다.
     */
    public boolean isTerminal() {
        return allowedNext.isEmpty();
    }

    /** 이 상태에서 {@code next} 로 갈 수 있는가. 자기 자신으로의 전이는 어디에도 없다. */
    public boolean canTransitionTo(CandidateStatus next) {
        return next != null && allowedNext.contains(next);
    }

    /** 허용 목적지. 수정 불가 — 호출자가 규칙을 늘릴 수 없다. */
    public Set<CandidateStatus> allowedNext() {
        return allowedNext;
    }
}
