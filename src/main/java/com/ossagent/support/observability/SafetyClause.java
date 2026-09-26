package com.ossagent.support.observability;

/**
 * 안전 경계 조항 — 어느 게이트인가.
 *
 * <p>⚠️ 지금 계측되는 것은 <b>{@code S5} 하나</b>다. 나머지를 미리 상수로 두지 않는 이유는
 * 「0 으로 고정된 카운터」가 <b>「막은 적 없다」와 「그 게이트가 아직 없다」를 구분하지
 * 못하기 때문</b>이다. 그 게이트를 만드는 이슈가 자기 상수를 함께 넣는다.
 *
 * <ul>
 *   <li>S-1(push 대상) — 코드가 아직 없다 (#22)</li>
 *   <li>S-2(draft 고정) — 게이트가 <b>DB {@code CHECK}</b> 에 있어 앱에서 셀 수 없다</li>
 *   <li>S-3(샌드박스) — 코드는 있으나(#17) <b>호출부</b>가 없어 실행이 0건이다 (#18)</li>
 * </ul>
 */
public enum SafetyClause {
    /** 대상 저장소 기여 규약 — {@code assertContributionAllowed} */
    S5
}
