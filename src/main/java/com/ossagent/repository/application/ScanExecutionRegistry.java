package com.ossagent.repository.application;

import java.util.Optional;

/**
 * 스캔 실행의 <b>진행 상태와 중복 방어</b>를 함께 맡는다.
 *
 * <h2>🔴 인터페이스로 둔 이유 — 구현이 바뀔 것이 예정돼 있다</h2>
 *
 * <p>현재 구현({@link InMemoryScanExecutionRegistry})은 프로세스 메모리다. 단일 프로세스가
 * 전제라 지금은 성립하지만, <b>인스턴스가 둘이 되는 순간 사라지는 것은 화면이 아니라
 * 중복 스캔 차단(FR-4)</b>이다. 그때 필요한 것은 DB 락이고 마이그레이션이 따라온다.
 *
 * <p><b>주인이 있다</b> — #26 완료조건에 「다중 인스턴스 대비 잠금(ShedLock 등) — 중복
 * 스캔 방지」가 명시돼 있고 #26 의 선행이 #14(이 이슈)다. 갈아끼울 이음매를 여기 남긴다.
 *
 * <h2>⚠️ {@link #tryStart} 와 {@link #release} 는 짝이다</h2>
 *
 * <p>{@code tryStart} 로 자리를 잡은 뒤 실행 제출이 실패하면 <b>반드시</b> {@code release}
 * 한다. 안 하면 그 저장소가 영구히 「진행 중」이 되어 이후 모든 요청이 409 로 막히고,
 * 상태가 메모리에 있어 <b>재기동 외에 복구 수단이 없다.</b>
 */
public interface ScanExecutionRegistry {

    /**
     * 자리를 잡는다 — 성공하면 {@link ScanExecutionState.Phase#QUEUED}.
     *
     * @return 이미 진행 중이면 {@code false} (FR-4)
     */
    boolean tryStart(Long repositoryId);

    /** 🔴 {@link #tryStart} 이후 실행에 들어가지 못했을 때 자리를 되돌린다. */
    void release(Long repositoryId);

    /** 스레드를 잡았다 — {@code QUEUED} → {@code RUNNING}. */
    void markRunning(Long repositoryId);

    void markSucceeded(Long repositoryId, ScanPipelineResult result);

    /** 🔴 실패가 아니다 — 규약이 막았거나 읽지 못했다. */
    void markSkipped(Long repositoryId, ScanPipelineResult result);

    /**
     * @param failureType 🔴 예외 <b>클래스 이름</b>만. 메시지를 넣지 않는다 (S-4)
     */
    void markFailed(Long repositoryId, ScanExecutionState.Stage stage, String failureType,
            ScanPipelineResult partial);

    /** 한 번도 돌지 않았으면 {@link Optional#empty()} — 호출자가 {@code idle} 로 표현한다. */
    Optional<ScanExecutionState> stateOf(Long repositoryId);
}
