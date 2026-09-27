package com.ossagent.support.testing.probe.candidate.application;

import com.ossagent.candidate.domain.ContributionCandidate;
import java.time.Clock;

/**
 * 「재시도 루프가 PR 까지 흘려보낸다」의 미끼 — 🔴 <b>S-2</b> · #21.
 *
 * <h2>🔴 이 미끼가 없으면 규칙이 0건을 검사하고 초록이 된다</h2>
 *
 * <p>{@code markPrCreated} 를 부르는 운영 코드는 <b>지금 하나도 없다.</b> PR 생성기는
 * #23 이 만든다. 그래서 「그 메서드를 부르는 타입이 0개」라는 규칙은 <b>판정기가 항상
 * {@code false} 를 돌려줘도 초록</b>이다 — {@code testing-philosophy.md} 요구 2 가
 * 정확히 이 상황을 말한다.
 *
 * <p><b>초록은 「막혔다」의 증거가 아니라 「지금 위반이 없다」의 증거다.</b>
 * 그 둘을 가르는 것이 이 클래스다.
 *
 * <h2>🔴 왜 허용목록이 아니라 여집합인가</h2>
 *
 * <p>기존 규칙 ①b 는 <b>허용목록</b>이다 — 사람 전이 메서드의 호출자를
 * {@code SelectCandidateUseCase} <b>하나로 고정</b>한다. {@code markPrCreated} 에 그대로
 * 붙이면 <b>「선정 UseCase 만 PR 을 만들 수 있다」</b>가 되어 의미가 뒤집힌다 —
 * 정당한 호출자는 #23 이고, 지금은 아무도 아니다.
 *
 * <p>그래서 이 규칙은 <b>「0개」</b>를 단언한다. #23 이 PR 생성기를 여는 날
 * <b>이 테스트가 함께 빨개지고</b>, 그것이 「실행기와 같은 PR 에서 연다」를 강제한다 —
 * {@code CandidateApprovalApiTest} 의 404 회귀와 같은 장치다.
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
    public void 루프가_알아서_PR_을_만든다(ContributionCandidate candidate, Clock clock) {
        candidate.markPrCreated(clock);
    }
}
