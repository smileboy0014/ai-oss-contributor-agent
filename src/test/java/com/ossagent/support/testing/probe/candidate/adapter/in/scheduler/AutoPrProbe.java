package com.ossagent.support.testing.probe.candidate.adapter.in.scheduler;

import com.ossagent.candidate.application.CreateDraftPrUseCase;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.PullRequest;
import java.time.Clock;

/**
 * 「자동으로 Draft PR 을 만든다」의 미끼 — S-6 <b>세 번째</b> 게이트 (#23).
 *
 * <h2>왜 미끼가 하나 더 필요한가</h2>
 *
 * <p>규칙 ①의 양성 대조가 {@code hasMessageContaining("AutoSelectProbe")} 로만 고정돼
 * 있었다. 그 상태에서는 <b>새 게이트를 목록에 빠뜨려도 테스트가 초록</b>이다 —
 * 기존 미끼 하나가 계속 물리기 때문이다. #23 검토에서 잡힌 구멍이고,
 * {@code testing-philosophy.md} 가드 요구 2(물림)가 <b>게이트마다</b> 필요하다는 뜻이다.
 *
 * <h2>이 클래스는 두 규칙을 <b>각각</b> 문다</h2>
 *
 * <table border="1">
 *   <tr><td>규칙 ①</td><td>{@link CreateDraftPrUseCase} 를 web 밖에서 부른다</td></tr>
 *   <tr><td>규칙 ①b</td><td>UseCase 를 <b>거치지 않고</b> 엔티티의 전이 메서드를 직접 부른다</td></tr>
 * </table>
 *
 * <p>🔴 ①b 가 따로 필요한 이유는 ①이 <b>타입</b>을 지목하기 때문이다. 자동 실행자가
 * 후보를 직접 꺼내 {@code markPrCreated} 를 부르면 UseCase 를 거치지 않아 ①에 걸리지
 * 않는다 — 그러면 <b>사람의 승인 없이 종단 {@code PR_CREATED}</b> 가 된다.
 *
 * <p>⚠ 스프링 스테레오타입을 붙이지 않는다. 이름이 {@code Test} 로 끝나지 않고
 * {@code @Test} 메서드도 없어 <b>실행되지 않는다.</b>
 *
 * <p>⚠️ 이 클래스를 「고쳐서 통과시키지」 않는다. 빨개지면 고칠 곳은 운영 코드다.
 */
public final class AutoPrProbe {

    private final CreateDraftPrUseCase gate;
    private final Clock clock;

    public AutoPrProbe(CreateDraftPrUseCase gate, Clock clock) {
        this.gate = gate;
        this.clock = clock;
    }

    /** 규칙 ①이 물어야 한다 — 스케줄러가 승인 게이트를 부른다. */
    public void 자동으로_PR_을_만든다(Long candidateId) {
        gate.create(candidateId);
    }

    /**
     * 규칙 ①b 가 물어야 한다 — UseCase 를 <b>우회해</b> 엔티티를 직접 전이시킨다.
     *
     * <p>이것이 ①만으로는 막히지 않는 모양이고, 그래서 {@code markPrCreated} 가
     * {@code 사람이_부르는_전이} 목록에 있어야 한다.
     */
    public void 게이트를_우회해_종단으로_보낸다(ContributionCandidate candidate,
            PullRequest pullRequest) {
        candidate.markPrCreated(pullRequest, clock);
    }
}
