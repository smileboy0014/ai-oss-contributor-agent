package com.ossagent.repository.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 파이프라인을 전용 스레드풀에서 돌린다 — {@link LaunchScanUseCase} 가 제출한다.
 *
 * <p>🔴 <b>별도 빈이라야 {@code @Async} 가 동작한다.</b> 같은 클래스 안에서 부르면
 * 프록시를 타지 않아 <b>조용히 동기 실행</b>되고, 증상이 「API 가 느리다」뿐이다.
 *
 * <h2>🔴 여기서 나간 예외는 사라진다</h2>
 *
 * <p>{@code @Async void} 메서드 밖으로 던진 예외는 호출자에게 도달하지 않는다
 * ({@code Future} 도 없다). 전부 잡아 레지스트리에 기록하지 않으면
 * <b>「202 를 받았는데 아무 일도 안 일어난 것처럼 보이는」</b> 상태가 된다 —
 * 진행 조회가 영원히 {@code RUNNING} 에 멈춘 채로.
 */
@Service
public class ScanExecutor {

    /** 🔴 전용 풀 이름. 기본 풀로 떨어지면 동시 1건(NFR-2)이 깨진다. */
    public static final String EXECUTOR_BEAN = "scanTaskExecutor";

    private static final Logger log = LoggerFactory.getLogger(ScanExecutor.class);

    private final ScanPipelineUseCase pipeline;
    private final ScanExecutionRegistry registry;

    public ScanExecutor(ScanPipelineUseCase pipeline, ScanExecutionRegistry registry) {
        this.pipeline = pipeline;
        this.registry = registry;
    }

    @Async(EXECUTOR_BEAN)
    public void execute(Long repositoryId) {
        MDC.put("repositoryId", String.valueOf(repositoryId));
        registry.markRunning(repositoryId);
        try {
            ScanPipelineResult result = pipeline.run(repositoryId);
            if (result.isSkipped()) {
                registry.markSkipped(repositoryId, result);
            } else {
                registry.markSucceeded(repositoryId, result);
            }
        } catch (ScanStageFailedException e) {
            // ⚠ 예외는 마지막 인자로 — 스택트레이스를 잃지 않는다. 그러나 레지스트리에는
            //   타입만 넣는다. 그 값이 진행 조회로 HTTP 에 나간다 (S-4)
            log.error("스캔 파이프라인 실패 repositoryId={} stage={}", repositoryId, e.stage(), e);
            registry.markFailed(repositoryId, e.stage(), e.failureType(), e.partial());
        } catch (RuntimeException e) {
            log.error("스캔 파이프라인이 알 수 없는 이유로 실패했다 repositoryId={}", repositoryId, e);
            registry.markFailed(repositoryId, null, e.getClass().getSimpleName(), null);
        } finally {
            MDC.remove("repositoryId");
        }
    }
}
