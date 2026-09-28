package com.ossagent.repository.application;

import com.ossagent.repository.domain.ScanPhase;
import com.ossagent.repository.domain.ScanStage;
import java.time.Instant;

/**
 * 한 저장소의 스캔 실행 상태 — 진행 조회(FR-3)가 내보내는 값.
 *
 * <p>🔴 <b>실패 사유에 예외 원문을 싣지 않는다</b> (S-4). {@link #failureType} 은 예외
 * <b>클래스 이름</b>이고 {@link #failureStage} 는 <b>우리 단계 어휘</b>다. 메시지에는
 * 대상 저장소 텍스트·모델 응답·요청 URL 이 실려 올 수 있다.
 *
 * <p>⚠️ 국면·단계 어휘({@link ScanPhase}·{@link ScanStage})는 <b>domain 에 있다</b> —
 * #26 이 실행 상태를 DB 로 옮기면서 엔티티가 같은 어휘를 써야 했고, domain 이
 * application 을 import 하면 의존 방향이 뒤집힌다. 이 record 자체는 {@code application}
 * 에 남는다 — {@link ScanPipelineResult} 가 다른 도메인의 application 값을 모으기 때문이다.
 *
 * @param lastResult 직전 실행의 집계. 아직 한 번도 안 돌았으면 {@code null}
 */
public record ScanExecutionState(
        Long repositoryId,
        ScanPhase phase,
        Instant startedAt,
        Instant finishedAt,
        ScanPipelineResult lastResult,
        ScanStage failureStage,
        String failureType,
        /** DB 구현만 채운다 — {@code null} 이면 「리스 개념이 없다」(메모리 대역) (#109) */
        Instant leaseExpiresAt) {

    /** 리스를 모르는 호출자용 — 대역·테스트가 쓴다. */
    public ScanExecutionState(Long repositoryId, ScanPhase phase, Instant startedAt,
            Instant finishedAt, ScanPipelineResult lastResult, ScanStage failureStage,
            String failureType) {
        this(repositoryId, phase, startedAt, finishedAt, lastResult, failureStage, failureType, null);
    }

    public static ScanExecutionState idle(Long repositoryId) {
        return new ScanExecutionState(repositoryId, ScanPhase.IDLE, null, null, null, null, null);
    }

    /** 진행 중인가 — 중복 방어(FR-4)가 보는 값이다. */
    public boolean isActive() {
        return phase != null && phase.isActive();
    }

    /**
     * 🔴 활성이라 적혀 있어도 리스가 지났으면 주인은 죽은 것이다 (#109). {@code kill -9} 뒤 진행 조회가
     * 2시간 동안 RUNNING 을 말하던 자리다. 리스를 모르면(메모리 대역) 만료가 아니다.
     */
    public boolean isLeaseExpired(Instant now) {
        return isActive() && leaseExpiresAt != null && now != null && !now.isBefore(leaseExpiresAt);
    }
}
