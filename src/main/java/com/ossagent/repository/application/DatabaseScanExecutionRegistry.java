package com.ossagent.repository.application;

import com.ossagent.repository.adapter.out.persistence.ScanExecutionJpaRepository;
import com.ossagent.repository.domain.ScanExecution;
import com.ossagent.repository.domain.ScanOutcome;
import com.ossagent.repository.domain.ScanPhase;
import com.ossagent.repository.domain.ScanStage;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ScanExecutionRegistry} 의 DB 구현 — 🔴 <b>중복 방어가 프로세스 경계를 넘는다</b>
 * (#26 FR-3).
 *
 * <h2>왜 ShedLock 이 아닌가</h2>
 *
 * <p>이슈 문구는 「ShedLock <b>등</b>」이라 특정 라이브러리를 강제하지 않았고, 검토 결과
 * 채택하지 않았다. 근거는 편의가 아니라 <b>락의 축이 다르다</b>는 것이다.
 *
 * <ul>
 *   <li>ShedLock 이 잠그는 단위는 {@code @Scheduled} <b>메서드 1개</b>다. 우리가 막아야
 *       하는 것은 <b>저장소 1건</b>이라, 인스턴스 A 가 저장소 1을 B 가 저장소 2를 도는
 *       것까지 직렬화된다 — 막을 이유가 없는데 막는다</li>
 *   <li>🔴 <b>결정적인 것</b> — {@code POST /api/repositories/{id}/scan} 은 컨트롤러가
 *       {@link LaunchScanUseCase} 를 직접 부른다. 스케줄러를 경유하지 않으므로
 *       ShedLock 이 <b>닿지 않는다.</b> 스케줄러와 API 가 같은 저장소를 동시에 스캔하는
 *       것을 못 막는다</li>
 *   <li>공식 DDL 이 벤더별이라 Q-2b-1(단일 SQL 한 벌)과 어긋난다</li>
 * </ul>
 *
 * <p>락은 <b>이미 올바른 자리에 있었다</b> — {@code tryStart} 다. 저장 매체가 프로세스
 * 메모리인 것만이 문제였고, 그래서 의존성을 더하지 않고 구현을 갈아끼웠다.
 * {@link ScanExecutionRegistry} javadoc 이 「갈아끼울 이음매를 여기 남긴다」고 적어 둔
 * 그대로다.
 *
 * <h2>🔴 리스 — 이 클래스가 없애지 <b>말아야</b> 할 것</h2>
 *
 * <p>메모리 구현에는 아무도 적어 두지 않은 안전장치가 있었다: <b>재기동이 상태를 지운다.</b>
 * DB 로 옮기면 그것이 사라진다. {@code lease-duration} 이 그 자리를 대신하고,
 * <b>유일한 조정 지점</b>이다.
 *
 * <p>⚠️ heartbeat 갱신 스레드를 두지 않는다. 스레드를 하나 더 만들면 그 스레드가 죽을 때
 * 같은 문제가 한 겹 더 생긴다. 스캔 상한보다 넉넉한 고정 리스면 충분하고,
 * {@code QUEUED → RUNNING} 전이에서 한 번 더 미는 것으로 큐 대기 시간을 흡수한다.
 */
@Component
public class DatabaseScanExecutionRegistry implements ScanExecutionRegistry {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseScanExecutionRegistry.class);

    private static final EnumSet<ScanPhase> ACTIVE_PHASES =
            EnumSet.of(ScanPhase.QUEUED, ScanPhase.RUNNING);

    private final ScanExecutionJpaRepository executions;
    private final ScanProperties properties;
    private final ScanInstanceId instanceId;
    private final Clock clock;

    public DatabaseScanExecutionRegistry(ScanExecutionJpaRepository executions,
            ScanProperties properties, ScanInstanceId instanceId, Clock clock) {
        this.executions = executions;
        this.properties = properties;
        this.instanceId = instanceId;
        this.clock = clock;
    }

    /**
     * 🔴 <b>검사와 기록이 한 문장이다.</b> {@code SELECT} 후 {@code UPDATE} 로 짜면 두
     * 인스턴스가 그 사이를 통과해 둘 다 스캔을 시작한다.
     *
     * <p>0행일 때 <b>행이 없는 것</b>과 <b>남이 잡고 있는 것</b>을 가른다. 합치면 등록
     * 버그가 영구 409 로 위장된다 — {@link ScanExecutionNotRegisteredException}.
     *
     * @throws ScanExecutionNotRegisteredException 실행 행이 없다 (등록 경로가 깨졌다)
     */
    @Override
    @Transactional
    public boolean tryStart(Long repositoryId) {
        Instant now = Instant.now(clock);
        int acquired = executions.acquire(repositoryId, ScanPhase.QUEUED, ACTIVE_PHASES,
                now, now.plus(properties.leaseDuration()), instanceId.value());
        if (acquired > 0) {
            return true;
        }
        if (!executions.existsById(repositoryId)) {
            // 🔴 「잠겨 있다」가 아니다. 조용히 false 로 번역하면 이 고장이 영구 409 로 보인다
            throw new ScanExecutionNotRegisteredException(repositoryId);
        }
        return false;
    }

    /** 🔴 자리를 되돌린다. 남기면 리스가 만료될 때까지 그 저장소가 막힌다. */
    @Override
    @Transactional
    public void release(Long repositoryId) {
        find(repositoryId).ifPresent(ScanExecution::release);
    }

    /**
     * {@code QUEUED} → {@code RUNNING}, 그리고 <b>리스를 한 번 더 민다.</b>
     *
     * <p>자리 잡기 시점에만 리스를 걸면 큐에서 기다린 시간이 리스를 갉아먹어, 정작 도는
     * 동안 만료돼 <b>돌고 있는 스캔을 남이 뺏는다.</b> heartbeat 가 아니다 — 스레드를 더
     * 만들지 않고 자연스러운 전이 한 번에 얹는다.
     */
    @Override
    @Transactional
    public void markRunning(Long repositoryId) {
        Instant now = Instant.now(clock);
        find(repositoryId).ifPresent(
                execution -> execution.markRunning(now, now.plus(properties.leaseDuration())));
    }

    @Override
    @Transactional
    public void markSucceeded(Long repositoryId, ScanPipelineResult result) {
        finish(repositoryId, ScanPhase.SUCCEEDED, result, null, null);
    }

    @Override
    @Transactional
    public void markSkipped(Long repositoryId, ScanPipelineResult result) {
        finish(repositoryId, ScanPhase.SKIPPED, result, null, null);
    }

    @Override
    @Transactional
    public void markFailed(Long repositoryId, ScanStage stage, String failureType,
            ScanPipelineResult partial) {
        finish(repositoryId, ScanPhase.FAILED, partial, stage, failureType);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ScanExecutionState> stateOf(Long repositoryId) {
        return find(repositoryId).map(this::toState);
    }

    private void finish(Long repositoryId, ScanPhase terminal, ScanPipelineResult result,
            ScanStage stage, String failureType) {
        Optional<ScanExecution> execution = find(repositoryId);
        if (execution.isEmpty()) {
            // 🔴 마감을 못 했다고 예외로 올리지 않는다 — 이것을 부르는 곳은 이미 실패
            //    경로(ScanExecutor 의 catch·finally)라, 여기서 던지면 원래 실패를 가린다
            log.error("스캔 실행 행이 없어 마감하지 못했다 repositoryId={} phase={}",
                    repositoryId, terminal);
            return;
        }
        execution.get().finish(terminal, Instant.now(clock), toOutcome(result), stage, failureType);
    }

    private Optional<ScanExecution> find(Long repositoryId) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수다");
        }
        return executions.findById(repositoryId);
    }

    /**
     * 🔴 <b>변환은 여기 한 곳에서만 한다.</b> 두 곳이 되면 필드가 늘 때 한쪽이 빠지고,
     * 빠진 쪽은 0 으로 저장되어 「돌았는데 아무 일도 없었다」로 보인다.
     */
    private ScanOutcome toOutcome(ScanPipelineResult result) {
        if (result == null) {
            return null;
        }
        return new ScanOutcome(result.issuesSaved(), result.issuesJudged(),
                result.candidatesAnalyzed(), result.candidatesRejected(),
                result.candidatesFailed(), result.candidatesSkipped(),
                result.hasMore(), result.delayedUntil(), result.skipReason());
    }

    private ScanExecutionState toState(ScanExecution execution) {
        ScanOutcome outcome = execution.outcome();
        return new ScanExecutionState(
                execution.getRepositoryId(),
                execution.getPhase(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                outcome == null ? null : new ScanPipelineResult(
                        outcome.issuesSaved(), outcome.issuesJudged(),
                        outcome.candidatesAnalyzed(), outcome.candidatesRejected(),
                        outcome.candidatesFailed(), outcome.candidatesSkipped(),
                        outcome.hasMore(), outcome.delayedUntil(), outcome.skipReason()),
                execution.getFailureStage(),
                execution.getFailureType());
    }
}
