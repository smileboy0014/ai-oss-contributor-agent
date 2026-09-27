package com.ossagent.candidate.domain;

import com.ossagent.agent.domain.AgentRunContext;

/**
 * 생성된 diff 를 리뷰하는 <b>능력</b> — 규율 ③ · 이슈 #20.
 *
 * <p>이름에 기술이 없다. 구현은 {@code candidate/adapter/out/llm/LlmDiffReviewer} 이고
 * 그쪽만 {@code LanguageModel} 을 안다 — {@code IssueAnalyst}/{@code LlmIssueAnalyst} 와 같은 배치다.
 *
 * <h2>🔴 관찰값만 돌려준다 — 임계는 호출자가 갖는다</h2>
 *
 * <p>{@code boolean} 이나 「통과/실패」를 돌려주지 않는다. {@code IssueAnalyst} 가
 * 「{@code REJECTED} 여부는 호출자가 임계로 정한다」로 못 박아 둔 것과 같은 이유다 —
 * <b>임계가 능력 안에 들어가면 그것을 바꾸려고 어댑터를 고치게 되고, 리뷰에 안 보인다.</b>
 *
 * <p>「몇 축이 위반이면 되돌리는가」는 재시도 루프의 판단이고 <b>#21</b> 이 정한다.
 *
 * <h2>🔴 선언을 여기서 한 번만 한다</h2>
 *
 * <p><b>능력 인터페이스는 구현을 싣는 PR 이 선언한다. 호출자는 import 만 한다.</b>
 * 이 PR 이 선언·값 타입·구현을 함께 싣는다 — #21 이 이것을 다시 선언하면 그것이 틀린 것이다.
 * ({@code ChangeVerifier} 가 #18·#19 로 갈려 중복 선언된 것이 그 규칙의 예외 사례다.)
 *
 * <h2>⚠️ 트랜잭션 밖에서 부른다</h2>
 *
 * <p>LLM 호출이다. 트랜잭션 안에 넣으면 응답을 기다리는 내내 DB 커넥션이 잡힌다 —
 * {@code architecture.md} §2.
 *
 * <p>🔴 <b>{@code AgentRun} 기록을 이 능력이 하지 않는다.</b> 노출되는 유일한
 * {@code LanguageModel} 빈이 {@code RecordingLanguageModel} 이라 <b>운영에서 기록을 건너뛸
 * 경로가 없다.</b> 여기서 또 기록하면 이중 기록이 된다 — 할 일은 {@code context} 를
 * 정확히 넘기는 것뿐이다(FR-6).
 */
public interface DiffReviewer {

    /**
     * 리뷰한다.
     *
     * @param context {@code candidateId} · {@code REVIEW} · {@code attempt}.
     *                🔴 {@code attempt} 는 호출자가 준다 — Q-6 이 「{@code CODE→VERIFY→REVIEW}
     *                한 바퀴가 1」로 확정했고 그 카운터의 주인은 #21 이다
     * @throws DiffReviewRejectedException            diff 가 상한을 넘었거나 응답이 스키마를
     *                                                만족하지 못했다. 🔴 <b>둘 다 재시도 대상이
     *                                                아니다</b> — 사유 enum 이 그것을 말한다
     * @throws com.ossagent.agent.domain.LlmException 호출 자체가 실패했다(타임아웃·절단·5xx).
     *                                                <b>이쪽은 재시도가 의미 있다</b>
     */
    DiffReview review(AgentRunContext context, DiffReviewRequest request);
}
