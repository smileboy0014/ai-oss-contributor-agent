package com.ossagent.repository.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 프로세스 메모리 구현 — Q-3 이 미분리라 단일 프로세스가 전제다.
 *
 * <p>⚠️ <b>알려진 한계 둘.</b> 재기동하면 상태가 사라지고, <b>인스턴스가 늘면 FR-4 가
 * 깨진다</b>(각 인스턴스가 자기 맵만 본다 → 같은 저장소를 동시에 스캔한다).
 * 후자가 진짜 문제이고 주인은 #26 이다 — {@link ScanExecutionRegistry} javadoc.
 *
 * <p>영속 흔적이 아주 없지는 않다 — {@code oss_repository.last_scanned_at} 이 남는다.
 * ⚠️ 다만 그것은 <b>요청 시각</b>이라 실패해도 전진한다. 재기동 후에는 성공한 스캔과
 * 실패한 스캔이 DB 상 구분되지 않는다.
 */
@Component
public class InMemoryScanExecutionRegistry implements ScanExecutionRegistry {

    private final Map<Long, ScanExecutionState> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryScanExecutionRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * 🔴 <b>검사와 기록이 원자적이어야 한다.</b> {@code containsKey} 후 {@code put} 으로
     * 짜면 두 요청이 그 사이를 통과해 <b>둘 다 스캔을 시작한다.</b>
     * {@code compute} 가 키 단위로 원자성을 준다.
     */
    @Override
    public boolean tryStart(Long repositoryId) {
        Instant now = Instant.now(clock);
        // ⚠ 「내가 잡았는가」를 반환값 비교로 알아내지 않는다. Clock 이 고정된 테스트에서는
        //   두 호출의 startedAt 이 같아 「이미 진행 중」을 「내가 잡았다」로 오판한다.
        //   람다 안에서 직접 표시한다
        boolean[] acquired = {false};
        states.compute(repositoryId, (id, current) -> {
            if (current != null && current.isActive()) {
                return current;
            }
            acquired[0] = true;
            return new ScanExecutionState(id, ScanExecutionState.Phase.QUEUED,
                    now, null, current == null ? null : current.lastResult(), null, null);
        });
        return acquired[0];
    }

    @Override
    public void release(Long repositoryId) {
        // 🔴 자리를 되돌린다. 남겨 두면 그 저장소가 영구히 409 다
        states.remove(repositoryId);
    }

    @Override
    public void markRunning(Long repositoryId) {
        states.computeIfPresent(repositoryId, (id, current) ->
                new ScanExecutionState(id, ScanExecutionState.Phase.RUNNING,
                        current.startedAt(), null, current.lastResult(), null, null));
    }

    @Override
    public void markSucceeded(Long repositoryId, ScanPipelineResult result) {
        finish(repositoryId, ScanExecutionState.Phase.SUCCEEDED, result, null, null);
    }

    @Override
    public void markSkipped(Long repositoryId, ScanPipelineResult result) {
        finish(repositoryId, ScanExecutionState.Phase.SKIPPED, result, null, null);
    }

    @Override
    public void markFailed(Long repositoryId, ScanExecutionState.Stage stage, String failureType,
            ScanPipelineResult partial) {
        finish(repositoryId, ScanExecutionState.Phase.FAILED, partial, stage, failureType);
    }

    @Override
    public Optional<ScanExecutionState> stateOf(Long repositoryId) {
        return Optional.ofNullable(states.get(repositoryId));
    }

    private void finish(Long repositoryId, ScanExecutionState.Phase phase,
            ScanPipelineResult result, ScanExecutionState.Stage stage, String failureType) {
        Instant now = Instant.now(clock);
        states.compute(repositoryId, (id, current) -> new ScanExecutionState(
                id,
                phase,
                current == null ? now : current.startedAt(),
                now,
                result,
                stage,
                failureType));
    }
}
