package com.ossagent.repository.application;

import com.ossagent.repository.domain.ScanPhase;
import com.ossagent.repository.domain.ScanStage;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ScanExecutionRegistry} 대역 — Q-9 「능력 대역」 층.
 *
 * <p>🔴 <b>원래 운영 구현이던 {@code InMemoryScanExecutionRegistry} 가 여기로 내려왔다</b>
 * (#26). 운영에는 DB 구현 하나만 남긴다 — 둘을 남기면 <b>어느 것이 뜨는지 배포 설정
 * 한 줄이 정하게</b> 되고, 그것은 S-2 의 「draft 플래그를 두면 언젠가 켜진다」·
 * S-3 의 「네트워크는 설정 키가 아니다」와 같은 문제다.
 *
 * <p>⚠️ <b>이 대역으로 FR-3(중복 방어)을 검증하지 않는다.</b> 여기 원자성은
 * {@code ConcurrentHashMap.compute} 가 주는 것이고, 운영이 막아야 하는 것은
 * <b>프로세스 경계를 넘는</b> 경쟁이다. 그것은 실 DB 가 아니면 검증되지 않는다 —
 * {@code DatabaseScanExecutionRegistryTest}(Testcontainers)가 본다.
 *
 * <p>여기서 보는 것은 <b>소비자의 흐름</b>이다 — 자리를 잡고 되돌리는가,
 * 어떤 경로로 끝나든 마감되는가.
 */
public class FakeScanExecutionRegistry implements ScanExecutionRegistry {

    private final Map<Long, ScanExecutionState> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public FakeScanExecutionRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * 🔴 <b>검사와 기록이 원자적이어야 한다.</b> {@code containsKey} 후 {@code put} 으로
     * 짜면 두 요청이 그 사이를 통과해 <b>둘 다 스캔을 시작한다.</b> 대역이라도 그 성질을
     * 흉내내야 소비자 테스트가 의미를 갖는다.
     */
    @Override
    public boolean tryStart(Long repositoryId) {
        Instant now = Instant.now(clock);
        // ⚠ 「내가 잡았는가」를 반환값 비교로 알아내지 않는다. Clock 이 고정된 테스트에서는
        //   두 호출의 startedAt 이 같아 「이미 진행 중」을 「내가 잡았다」로 오판한다
        boolean[] acquired = {false};
        states.compute(repositoryId, (id, current) -> {
            if (current != null && current.isActive()) {
                return current;
            }
            acquired[0] = true;
            return new ScanExecutionState(id, ScanPhase.QUEUED,
                    now, null, current == null ? null : current.lastResult(), null, null);
        });
        return acquired[0];
    }

    @Override
    public void release(Long repositoryId) {
        states.remove(repositoryId);
    }

    @Override
    public void markRunning(Long repositoryId) {
        states.computeIfPresent(repositoryId, (id, current) ->
                new ScanExecutionState(id, ScanPhase.RUNNING,
                        current.startedAt(), null, current.lastResult(), null, null));
    }

    @Override
    public void markSucceeded(Long repositoryId, ScanPipelineResult result) {
        finish(repositoryId, ScanPhase.SUCCEEDED, result, null, null);
    }

    @Override
    public void markSkipped(Long repositoryId, ScanPipelineResult result) {
        finish(repositoryId, ScanPhase.SKIPPED, result, null, null);
    }

    @Override
    public void markFailed(Long repositoryId, ScanStage stage, String failureType,
            ScanPipelineResult partial) {
        finish(repositoryId, ScanPhase.FAILED, partial, stage, failureType);
    }

    @Override
    public Optional<ScanExecutionState> stateOf(Long repositoryId) {
        return Optional.ofNullable(states.get(repositoryId));
    }

    private void finish(Long repositoryId, ScanPhase phase,
            ScanPipelineResult result, ScanStage stage, String failureType) {
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
