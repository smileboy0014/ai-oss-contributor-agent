package com.ossagent.candidate.application;

import com.ossagent.candidate.domain.AgentRun;
import com.ossagent.candidate.domain.ImplementationAlreadyRunningException;
import com.ossagent.candidate.domain.StatusTransition;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 착수 게이트 — <b>동기로 전이하고, 비동기로 돈다</b> (#106 · S-6 두 번째 게이트).
 *
 * <pre>
 * HTTP 스레드:  admit(판정) → 자리 잡기(후보·저장소 잠금) → start(SELECTED→IMPLEMENTING) → 제출 → 202
 * 백그라운드:   준비(계획·clone·워밍) → CODE→VERIFY→REVIEW 루프          ← ImplementExecutor
 * </pre>
 *
 * <h2>🔴 전이가 HTTP 스레드에 남는 이유</h2>
 *
 * <p>「사람이 착수를 지시한다」의 증거인 {@code SELECTED → IMPLEMENTING} 은 사람이 누른 그 요청 안에서
 * 일어나야 한다. 백그라운드가 전이하면 승인 게이트를 부르는 것이 자동 실행자가 된다 — S-6 이 막는
 * 바로 그 모양이고, {@code ApprovalGateArchitectureTest} 가 「web 뒤의 UseCase」를 요구하는 이유다.
 * 그래서 준비 단계의 실패는 이제 「전이 앞」이 아니라 <b>미룸(#98)으로 SELECTED 복귀</b>다 — 결과는 같다.
 *
 * <p>⚠️ {@code executorReady()} 판정은 그대로 동기다 — 실행기 없이 전이하면 후보가 {@code IMPLEMENTING}
 * 에 갇힌다는 #18 의 이유가 그대로다(503).
 */
@Service
public class LaunchImplementationUseCase {

    private static final Logger log = LoggerFactory.getLogger(LaunchImplementationUseCase.class);

    private final CandidateImplementationWriter writer;
    private final ImplementCandidateUseCase implement;
    private final ImplementExecutor executor;
    private final ImplementationRegistry registry;
    private final CandidateRetryWriter retries;

    public LaunchImplementationUseCase(CandidateImplementationWriter writer,
            ImplementCandidateUseCase implement, ImplementExecutor executor,
            ImplementationRegistry registry, CandidateRetryWriter retries) {
        this.writer = writer;
        this.implement = implement;
        this.executor = executor;
        this.registry = registry;
        this.retries = retries;
    }

    /**
     * @return 이 요청이 만든 전이 — {@code SELECTED → IMPLEMENTING}. 🔴 이제 낡지 않은 값이다:
     *         초안은 루프가 다 돈 뒤에야 돌아와 {@code READY_FOR_PR} 인 후보를 {@code IMPLEMENTING} 이라 했다
     * @throws ImplementationAlreadyRunningException 이 후보 또는 같은 저장소의 다른 후보가 진행 중 → 409
     */
    public StatusTransition launch(Long candidateId) {
        if (candidateId == null) {
            throw new IllegalArgumentException("후보 식별자는 필수입니다");
        }
        assertNoTransaction();

        // 🔴 판정(403 · 409 · 503)은 여전히 동기다 — 사람이 누른 요청에 바로 답한다
        CandidateImplementationWriter.Admission admission =
                writer.admit(candidateId, implement.executorReady());

        if (!registry.tryStart(candidateId, admission.repositoryId())) {
            boolean sameCandidate = registry.stateOf(candidateId)
                    .map(state -> state.phase().isActive()).orElse(false);
            throw new ImplementationAlreadyRunningException(candidateId,
                    sameCandidate ? ImplementationAlreadyRunningException.Reason.ALREADY_RUNNING
                            : ImplementationAlreadyRunningException.Reason.REPOSITORY_BUSY);
        }
        try {
            // 🔴 게이트 전이 — 사람이 누른 이 스레드에서 (S-6)
            CandidateImplementationWriter.ImplementationStart start =
                    writer.start(candidateId, implement.executorReady());
            try {
                executor.execute(start);
            } catch (RejectedExecutionException e) {
                // 🔴 전이는 이미 커밋됐다. IMPLEMENTING 에 갇히지 않게 미룬다 — 사람이 다시 누른다 (#98)
                retries.defer(candidateId, start.attempt(), AgentRun.Stage.CODE,
                        "착수 큐가 가득 차 미룬다 — 잠시 뒤 다시 착수한다");
                registry.release(candidateId);
                log.warn("착수 큐가 찼다 candidateId={} — 거절한다", candidateId);
                throw new ImplementationAlreadyRunningException(candidateId,
                        ImplementationAlreadyRunningException.Reason.QUEUE_FULL);
            }
            return start.transition();
        } catch (RuntimeException e) {
            // start 가 거절됐거나(정책이 그 사이 바뀜) 제출이 실패했다 — 자리를 남기지 않는다
            if (!(e instanceof ImplementationAlreadyRunningException)) {
                registry.release(candidateId);
            }
            throw e;
        }
    }

    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "착수 기동을 트랜잭션 안에서 부를 수 없다 — 호출자의 @Transactional 을 제거한다");
        }
    }
}
