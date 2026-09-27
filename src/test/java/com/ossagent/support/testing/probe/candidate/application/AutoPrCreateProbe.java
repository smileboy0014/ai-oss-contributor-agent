package com.ossagent.support.testing.probe.candidate.application;

import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.PullRequest;
import java.time.Clock;

/**
 * 「재시도 루프가 PR 까지 흘려보낸다」의 미끼 — 🔴 <b>S-2</b> · #21.
 *
 * <h2>🔴 이 미끼가 없으면 규칙이 고장 나도 초록이다</h2>
 *
 * <p>{@code markPrCreated} 를 부르는 운영 코드는 <b>단 하나</b>({@code CandidatePrWriter})다.
 * 「그 하나만 부른다」는 규칙은 위반이 0건인 상태라 <b>판정기가 항상 {@code false} 를
 * 돌려줘도 초록</b>이다 — {@code testing-philosophy.md} 요구 2 가 정확히 이 상황을 말한다.
 *
 * <p><b>초록은 「막혔다」의 증거가 아니라 「지금 위반이 없다」의 증거다.</b>
 * 그 둘을 가르는 것이 이 클래스다.
 *
 * <h2>📌 이 규칙은 「0개」에서 「그 하나만」으로 조여졌다 (#23 머지)</h2>
 *
 * <p>#21 이 이 규칙을 세울 때는 {@code markPrCreated} 를 부르는 곳이 <b>하나도 없었고</b>
 * 규칙도 「0개」였다. 근거는 「정당한 호출자는 #23 이고 지금은 아무도 아니다」였고,
 * <b>#23 이 여는 날 이 테스트가 함께 빨개지는 것</b>이 의도된 장치였다.
 *
 * <p>그 일이 실제로 일어났다 — #23 이 {@code CandidatePrWriter} 를 만들며 이 테스트가
 * 깨졌고, 그래서 <b>허용 호출자를 명시적으로 적었다.</b> 🔴 테스트를 지우고 연 것이
 * 아니라 <b>허용 목록에 한 줄을 더한 것</b>이고, 그 한 줄이 리뷰에 보인다.
 *
 * <p>⚠️ 지금은 허용목록이 맞다 — 정당한 호출자가 <b>실재</b>하기 때문이다.
 * 기존 규칙 ①b 를 그대로 재사용했다면 호출자가 {@code SelectCandidateUseCase} 로
 * 고정되어 <b>「선정 UseCase 만 PR 을 만들 수 있다」</b>가 됐을 것이다.
 *
 * <h2>무엇이 걸려 있나</h2>
 *
 * <p>{@code READY_FOR_PR → PR_CREATED} 는 <b>세 번째 승인 게이트</b>를 지나는 전이다.
 * 루프가 그것을 스스로 밟으면 <b>검증되지 않은 AI 코드가 메인테이너의 리뷰 큐로 간다</b> —
 * OSS 에서 그 행동은 스팸으로 취급되고 계정이 차단된다(S-2).
 *
 * <p>⚠ 스프링 스테레오타입을 붙이지 않는다. 🔴 아무도 부르지 않는다.
 */
public final class AutoPrCreateProbe {

    /** 이 저장소가 <b>절대 하지 않기로 한 것</b>을 그대로 적어 둔 것이다 — 규칙이 물어야 한다. */
    public void 루프가_알아서_PR_을_만든다(ContributionCandidate candidate,
            PullRequest pullRequest, Clock clock) {
        candidate.markPrCreated(pullRequest, clock);
    }
}
