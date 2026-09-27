package com.ossagent.support.observability;

/**
 * 안전 경계 조항 — 어느 게이트인가.
 *
 * <p>⚠️ 지금 계측되는 것은 <b>{@code S5} 하나</b>다. 나머지를 미리 상수로 두지 않는 이유는
 * 「0 으로 고정된 카운터」가 <b>「막은 적 없다」와 「그 게이트가 아직 없다」를 구분하지
 * 못하기 때문</b>이다. 그 게이트를 만드는 이슈가 자기 상수를 함께 넣는다.
 *
 * <ul>
 *   <li>S-1(push 대상) — 어설션이 {@code GitHubWriteClient} 에 있고 실패가 <b>예외</b>다.
 *       계측을 더하려면 그쪽에 붙어야 한다 (#22)</li>
 *   <li>S-3(샌드박스) — 코드는 있으나(#17) <b>호출부</b>가 없어 실행이 0건이다 (#18)</li>
 * </ul>
 */
public enum SafetyClause {

    /**
     * 🔴 <b>Draft PR 생성 승인 게이트</b> — {@code CreateDraftPrUseCase} (#23).
     *
     * <p>⚠️ <b>「draft 고정」을 세는 것이 아니다.</b> 그쪽 게이트는 DB {@code CHECK} 와
     * 단일값 enum 이라 앱에서 셀 수 없고, 이 상수가 생기기 전 javadoc 이 그 이유로
     * S-2 를 제외해 두었다.
     *
     * <p>여기서 세는 것은 <b>「사람이 눌렀고, 후보가 PR 을 열어도 되는 상태였는가」</b>다.
     * #23 이 그 게이트를 처음 만들었으므로 이제 셀 대상이 있다 — 「0 으로 고정된 카운터」가
     * 아니다.
     */
    S2,

    /** 대상 저장소 기여 규약 — {@code assertContributionAllowed} */
    S5
}
