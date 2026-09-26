package com.ossagent.repository.application;

import java.time.Instant;

/**
 * 한 저장소의 스캔 실행 상태 — 진행 조회(FR-3)가 내보내는 값.
 *
 * <p>🔴 <b>실패 사유에 예외 원문을 싣지 않는다</b> (S-4). {@link #failureType} 은 예외
 * <b>클래스 이름</b>이고 {@link #failureStage} 는 <b>우리 단계 어휘</b>다. 메시지에는
 * 대상 저장소 텍스트·모델 응답·요청 URL 이 실려 올 수 있다.
 *
 * @param lastResult 직전 실행의 집계. 아직 한 번도 안 돌았으면 {@code null}
 */
public record ScanExecutionState(
        Long repositoryId,
        Phase phase,
        Instant startedAt,
        Instant finishedAt,
        ScanPipelineResult lastResult,
        Stage failureStage,
        String failureType) {

    /**
     * 실행 국면.
     *
     * <p>🔴 <b>{@code QUEUED} 를 {@code RUNNING} 과 가르는 것이 의도다.</b> 풀이
     * {@code corePoolSize=1} 이라 두 번째 저장소는 실제로는 <b>큐에서 대기</b> 중인데,
     * 어휘가 없으면 「돌고 있다」로 보인다. 동시 1건(NFR-2)이 실제로 지켜지는지
     * 사람이 확인할 수 있는 유일한 창이다.
     */
    public enum Phase {
        /** 한 번도 돌지 않았거나 마지막 실행이 끝났다 */
        IDLE,
        /** 제출됐고 스레드를 기다린다 */
        QUEUED,
        /** 돌고 있다 */
        RUNNING,
        /** 끝났다 */
        SUCCEEDED,
        /** 🔴 실패가 아니다 — 규약이 막았거나 지연이다. {@link ScanPipelineResult#skipReason} 참조 */
        SKIPPED,
        /** 실패했다. {@link #failureStage}·{@link #failureType} 에 사유 */
        FAILED
    }

    /** 파이프라인 단계 — 어디서 멈췄는지 사람이 읽을 어휘. */
    public enum Stage {
        POLICY,
        SCAN,
        FILTER,
        ANALYZE
    }

    public static ScanExecutionState idle(Long repositoryId) {
        return new ScanExecutionState(repositoryId, Phase.IDLE, null, null, null, null, null);
    }

    /** 진행 중인가 — 중복 방어(FR-4)가 보는 값이다. */
    public boolean isActive() {
        return phase == Phase.QUEUED || phase == Phase.RUNNING;
    }
}
