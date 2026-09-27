package com.ossagent.support.observability;

/**
 * 안전 경계 조항 — 어느 게이트인가.
 *
 * <p>⚠️ 상수를 미리 두지 않는다. 「0 으로 고정된 카운터」가 <b>「막은 적 없다」와 「그 게이트가
 * 아직 없다」를 구분하지 못하기 때문</b>이다. 그 게이트를 만드는 이슈가 자기 상수를 함께 넣는다.
 *
 * <ul>
 *   <li>S-3(샌드박스) — 실행 자체는 {@code SandboxChangeVerifier} 가 부르지만 「막았다/통과했다」로
 *       셀 판정 지점이 아직 없다. 컨테이너 스펙이 타입으로 강제돼 런타임 판정이 없기 때문이다</li>
 *   <li>S-6(승인 게이트) — 세 게이트의 거부는 409 로 나가고 {@code S2}·{@code S5} 가 그중 둘을 센다</li>
 * </ul>
 */
public enum SafetyClause {

    /**
     * 🔴 <b>Fork 에 쓰기</b> — {@code CreateDraftPrUseCase.publishToFork} 가 push 를 마친 뒤 통과를 센다.
     *
     * <p>차단 쪽은 {@code GitHubWriteClient} 의 owner 어설션이 <b>예외</b>로 끊으므로 그 카운트는
     * 여기 오지 않는다 — {@code UpstreamWriteAttemptException} 이 올라오면 그 자체가 신호다.
     * 통과를 세는 이유는 {@code logging.md} 의 「통과한 것도 남긴다」와 같다: 사고 뒤 「그때 어설션이
     * 돌았는가」를 증명할 수 있어야 한다 (#23 이 push 를 배선하며 추가).
     */
    S1,

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
