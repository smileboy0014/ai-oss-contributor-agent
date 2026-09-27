package com.ossagent.repository.application;

import com.ossagent.repository.domain.ScanPhase;
import com.ossagent.repository.domain.ScanStage;
import java.util.Optional;

/**
 * 스캔 실행의 <b>진행 상태와 중복 방어</b>를 함께 맡는다.
 *
 * <h2>인터페이스로 둔 이유 — ✅ <b>구현이 실제로 바뀌었다</b> (#26)</h2>
 *
 * <p>처음 구현({@code InMemoryScanExecutionRegistry})은 프로세스 메모리였다. 단일 프로세스가
 * 전제라 동작했지만, <b>인스턴스가 둘이 되는 순간 사라지는 것은 화면이 아니라 중복 스캔
 * 차단(FR-4)</b>이었다. #26 이 {@link DatabaseScanExecutionRegistry} 로 갈아끼웠고
 * 메모리 구현은 <b>운영에서 사라졌다</b> — 둘을 남기면 어느 것이 뜨는지 배포 설정 한 줄이
 * 정하게 된다.
 *
 * <h2>⚠️ {@link #tryStart} 와 {@link #release} 는 짝이다</h2>
 *
 * <p>{@code tryStart} 로 자리를 잡은 뒤 실행 제출이 실패하면 <b>반드시</b> {@code release}
 * 한다. 안 하면 그 저장소가 <b>리스가 만료될 때까지</b> 「진행 중」이 되어 모든 요청이
 * 409 로 막힌다.
 *
 * <p>🔴 <b>「재기동하면 풀린다」는 더 이상 참이 아니다.</b> 상태가 DB 에 있다.
 * 풀어 주는 것은 리스 만료 하나뿐이고, 그래서 리스가 <b>있어야만</b> 이 구조가 성립한다 —
 * 없으면 {@code release} 를 빠뜨린 경로 하나가 그 저장소를 영구히 잠근다.
 */
public interface ScanExecutionRegistry {

    /**
     * 자리를 잡는다 — 성공하면 {@link ScanPhase#QUEUED}.
     *
     * <p>🔴 <b>검사와 기록이 원자적이어야 한다.</b> 「읽고 → 판단하고 → 쓴다」로 구현하면
     * 두 요청이 그 사이를 통과해 <b>둘 다 스캔을 시작한다.</b>
     *
     * @return 이미 진행 중이면 {@code false} (FR-4). 🔴 <b>「실행 행이 없다」를 여기에
     *         접지 않는다</b> — 그것은 등록 경로가 깨졌다는 뜻이고, {@code false} 로
     *         번역하면 <b>영구 409 로 위장</b>된다
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
    void markFailed(Long repositoryId, ScanStage stage, String failureType,
            ScanPipelineResult partial);

    /**
     * ⚠️ <b>빈 값의 뜻이 구현에 따라 다르다.</b> 메모리 구현에서는 「한 번도 안 돌았다」였지만,
     * DB 구현에서는 행이 항상 존재하므로 그것이 {@link ScanPhase#IDLE} 로 표현되고
     * 빈 값은 <b>「실행 행이 없다」</b>(등록 경로가 깨졌다)를 뜻한다.
     *
     * <p>호출자는 어느 쪽이든 {@code idle} 로 표현한다 — 🔴 <b>조회가 등록 고장을 500 으로
     * 만들 이유가 없다.</b> 같은 상태를 {@link #tryStart} 는 예외로 드러낸다.
     */
    Optional<ScanExecutionState> stateOf(Long repositoryId);
}
