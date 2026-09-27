package com.ossagent.candidate.domain;

/**
 * AI diff 리뷰의 판정 — 이슈 #20.
 *
 * <h2>🔴 왜 셋인가 — 둘로 두면 방어가 스스로를 잠근다</h2>
 *
 * <p>초안은 {@code PASS}·{@code CHANGES_REQUESTED} 둘이었다. 그러면 모델이 「판단할 수
 * 없다」고 답할 때 <b>어느 쪽도 맞지 않는다.</b>
 *
 * <ul>
 *   <li>{@code PASS} 로 접으면 — 검증되지 않은 diff 가 통과한다</li>
 *   <li>{@code CHANGES_REQUESTED} 로 접으면 — <b>고칠 수 없는 것에 재시도 예산
 *       3바퀴(Q-6)를 통째로 태우고</b> 후보가 코드 문제 없이 {@code FAILED} 로 떨어진다</li>
 * </ul>
 *
 * <p>Q-8 이 「못 읽음 ≠ 금지」로 가른 것, {@code Issue.filterResult} 가
 * {@code UNDECIDED} 를 따로 둔 것과 <b>같은 축</b>이다.
 *
 * <h2>🔴 호출자 규약 — 통과 판정을 여집합으로 쓰지 않는다</h2>
 *
 * <pre>
 * if (verdict != CHANGES_REQUESTED) { … }   // ❌ 판정 불가가 조용히 통과로 접힌다
 * if (verdict == PASS)              { … }   // ✅
 * </pre>
 */
public enum ReviewVerdict {

    /** 문제를 찾지 못했다. <b>「좋은 코드」라는 뜻이 아니라 「이 네 축에서 걸리지 않았다」</b>이다 */
    PASS,

    /** 문제를 찾았다. 🔴 <b>재시도가 의미 있다</b> — 고칠 대상이 있다 */
    CHANGES_REQUESTED,

    /**
     * 🔴 <b>판정할 근거가 없다.</b> 모델이 판단을 거부했거나 판정을 담지 않았다.
     *
     * <p>🔴 <b>재시도 대상이 아니다</b> — 같은 입력에 같은 결과다. 재시도하면 Q-6 예산만 태운다.
     *
     * <p>⚠️ <b>전송 계층 실패는 여기 오지 않는다.</b> 절단·타임아웃·5xx 는
     * {@code LlmException} 으로 어댑터가 던진다 — #10 의 「잘린 JSON 을 소비자가 파싱하게
     * 두지 않는다」. 이 값은 <b>응답은 정상인데 판정이 없는</b> 경우다.
     */
    UNDETERMINED;

    /**
     * 🔴 <b>통과인가.</b> 여집합({@code != CHANGES_REQUESTED})으로 쓰지 않게 하려고 둔다 —
     * 그 한 줄에서 {@link #UNDETERMINED} 가 조용히 통과로 접힌다.
     */
    public boolean passed() {
        return this == PASS;
    }

    /**
     * 🔴 <b>재시도하면 결과가 달라질 수 있는가</b> — 판정을 enum 자신이 갖는다.
     *
     * <p>호출자(#21)가 {@code switch} 로 가르게 두면 <b>새 값이 추가될 때 기본값으로
     * 「재시도함」에 떨어진다.</b> 여기 두면 값을 더하는 사람이 이 메서드를 보게 된다.
     */
    public boolean warrantsRetry() {
        return this == CHANGES_REQUESTED;
    }
}
