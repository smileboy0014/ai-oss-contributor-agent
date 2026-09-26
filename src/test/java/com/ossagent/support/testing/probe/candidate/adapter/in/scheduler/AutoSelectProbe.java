package com.ossagent.support.testing.probe.candidate.adapter.in.scheduler;

import com.ossagent.candidate.application.SelectCandidateUseCase;

/**
 * 「스케줄러가 승인 게이트를 부른다」의 미끼 — S-6.
 *
 * <p>패키지가 {@code …adapter.in.scheduler} 라, {@code ApprovalGateArchitectureTest} 의
 * 「스케줄러·이벤트는 승인 게이트에 닿지 못한다」 규칙이 <b>반드시 이것을 물어야 한다.</b>
 *
 * <h2>왜 미끼가 필요한가</h2>
 *
 * <p>운영 코드의 스케줄러({@code ScanScheduler} · #14)는 스캔만 기동하고 승인 게이트를
 * 부르지 않는다. 그래서 그 규칙은 <b>위반 0건으로 초록</b>이 되고, 규칙이 통째로 잘못
 * 쓰여 있어도 아무도 모른다. 정작 그 규칙이 필요해지는 시점은 <b>누군가 스케줄러에서
 * 후보를 건드리기 시작하는 때</b>이고, 그때 고장 나 있으면 S-6 이 조용히 뚫린다.
 *
 * <p>「자동으로 흘려보내지 않는다」는 이 제품의 정의 그 자체라(PRD §20),
 * 그 방어가 도는지를 <b>돌기 전에</b> 확인해 둔다.
 *
 * <p>⚠ 스프링 스테레오타입을 붙이지 않는다 — test 클래스 디렉토리가 런타임 클래스패스에
 * 있어 {@code com.ossagent} 컴포넌트 스캔에 실제로 잡힌다.
 * 🔴 <b>진짜 스케줄러가 아니다.</b> {@code @Scheduled} 도 없고 아무도 부르지 않는다.
 */
public final class AutoSelectProbe {

    private final SelectCandidateUseCase gate;

    public AutoSelectProbe(SelectCandidateUseCase gate) {
        this.gate = gate;
    }

    /** 이 저장소가 <b>절대 하지 않기로 한 것</b>을 그대로 적어 둔 것이다 — 규칙이 물어야 한다. */
    public void 자동으로_고른다(Long candidateId) {
        gate.select(candidateId);
    }
}
