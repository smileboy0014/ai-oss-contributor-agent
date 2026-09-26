package com.ossagent.support.testing.probe.candidate.application;

import com.ossagent.candidate.domain.ContributionCandidate;
import java.time.Clock;

/**
 * 「자동 실행자가 엔티티를 직접 부른다」의 미끼 — S-6 · Q-5 확정 ②.
 *
 * <h2>🔴 이 미끼가 대표하는 위험이 실재한다</h2>
 *
 * <p>「스케줄러는 승인 게이트를 부르지 못한다」를 <b>패키지 이름으로</b> 세우면
 * ({@code ..adapter.in.scheduler..}) 이 저장소의 <b>진짜 자동 실행자를 놓친다.</b>
 * {@code ScanExecutor}(`@Async`)는 {@code repository.application} 에 있고
 * {@code LaunchScanUseCase} 도 마찬가지다 — 둘 다 그 패키지 밖이다.
 * 「아직 그런 것이 없어서 이론적 한계」가 아니라 <b>이미 있고 마침 게이트를 안 부를 뿐</b>이다.
 *
 * <p>두 번째 구멍이 더 얇다. 규칙이 {@code SelectCandidateUseCase} 를 <b>지목</b>하면,
 * 자동 실행자가 후보를 직접 꺼내 {@link ContributionCandidate#cancelSelection(Clock)} 을
 * 부르는 경로는 <b>UseCase 를 거치지 않아 걸리지 않는다.</b>
 * {@code selectedAt} 쓰기 규칙이 대신 잡아 주지도 않는다 — 취소는 그 필드를 건드리지 않는다.
 *
 * <p>그래서 이 미끼는 <b>UseCase 를 우회해 엔티티를 직접</b> 부른다.
 * 그것을 무는 규칙이 없으면 Q-5 의 「자동 취소 경로를 만들지 않는다」는 글로만 남는다.
 *
 * <p>⚠ 스프링 스테레오타입을 붙이지 않는다. 🔴 아무도 부르지 않는다.
 */
public final class AutoCancelProbe {

    /** 이 저장소가 <b>절대 하지 않기로 한 것</b>을 그대로 적어 둔 것이다 — 규칙이 물어야 한다. */
    public void 자동으로_취소한다(ContributionCandidate candidate, Clock clock) {
        candidate.cancelSelection(clock);
    }
}
