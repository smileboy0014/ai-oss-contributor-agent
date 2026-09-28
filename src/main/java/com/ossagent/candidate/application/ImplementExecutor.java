package com.ossagent.candidate.application;

import com.ossagent.candidate.application.ImplementationRegistry.ImplementationPhase;
import com.ossagent.candidate.domain.ImplementationDeferredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 착수의 <b>백그라운드 구간</b> — 준비(계획·clone·워밍) → 루프 — #106.
 *
 * <p>🔴 <b>별도 빈이라야 {@code @Async} 가 동작한다.</b> 같은 클래스 안에서 부르면 프록시를 타지 않고
 * 동기로 돈다 — {@code ScanExecutor} 와 같은 이유로 갈라져 있다.
 *
 * <p>🔴 <b>전이는 여기서 하지 않는다.</b> {@code SELECTED → IMPLEMENTING} 은 사람이 누른 HTTP 스레드에서
 * {@code LaunchImplementationUseCase} 가 끝냈고, 여기 오는 것은 이미 {@code IMPLEMENTING} 인 후보다.
 * 승인 게이트는 그대로 web 뒤에 있다 (S-6).
 *
 * <p>{@code @Async void} 밖으로 던진 예외는 아무에게도 도달하지 않는다. 그래서 전부 여기서 잡아
 * 레지스트리에 <b>타입만</b> 남긴다 — 그 값이 진행 조회로 HTTP 에 나간다 (S-4).
 */
@Service
public class ImplementExecutor {

    public static final String EXECUTOR_BEAN = "implementTaskExecutor";

    private static final Logger log = LoggerFactory.getLogger(ImplementExecutor.class);

    private final ImplementCandidateUseCase implement;
    private final ImplementationRegistry registry;

    public ImplementExecutor(ImplementCandidateUseCase implement, ImplementationRegistry registry) {
        this.implement = implement;
        this.registry = registry;
    }

    @Async(EXECUTOR_BEAN)
    public void execute(CandidateImplementationWriter.ImplementationStart start) {
        Long candidateId = start.candidateId();
        try {
            MDC.put("candidateId", String.valueOf(candidateId));
            registry.markPhase(candidateId, ImplementationPhase.PREPARING);
            implement.continueAfterStart(start);
            registry.finish(candidateId, ImplementationPhase.SUCCEEDED, null);
        } catch (ImplementationDeferredException e) {
            // 후보는 SELECTED 로 되돌아가 있다 — 사람이 다시 누른다 (#98). 메시지는 우리 어휘다
            log.warn("착수 미룸 candidateId={}", candidateId);
            registry.finish(candidateId, ImplementationPhase.DEFERRED, e.getMessage());
        } catch (RuntimeException e) {
            // 후보 상태(FAILED 등)는 UseCase 가 이미 DB 에 남겼다. 여기는 진행 조회용 라벨뿐이다
            log.error("착수 실패 candidateId={} type={}", candidateId, e.getClass().getSimpleName(), e);
            registry.finish(candidateId, ImplementationPhase.FAILED, e.getClass().getSimpleName());
        } catch (Error e) {
            // OOM·StackOverflow — 자리를 비우고 다시 던진다. 삼키면 저장소 잠금이 영구히 남는다
            registry.finish(candidateId, ImplementationPhase.FAILED, e.getClass().getSimpleName());
            throw e;
        } finally {
            MDC.remove("candidateId");
        }
    }
}
