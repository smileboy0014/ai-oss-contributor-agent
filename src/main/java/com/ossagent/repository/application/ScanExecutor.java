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
        // 🔴 markRunning 과 MDC 를 try 「안」에 둔다. 밖에 두면 거기서 던졌을 때 상태가
        //    QUEUED 로 굳고 isActive() 가 참이라 그 저장소가 영구 409 가 된다
        try {
            MDC.put("repositoryId", String.valueOf(repositoryId));
            registry.markRunning(repositoryId);

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
            // 🔴 마지막 안전망. Error(OOM·StackOverflow)나 markFailed 자체의 실패로
            //    위 catch 를 빠져나가면 상태가 RUNNING 에 굳는다 — 그 저장소는 영구 409 이고
            //    상태가 메모리에 있어 재기동 외에 복구 수단이 없다.
            //    Error 를 삼키지는 않는다(재던져 올라간다). 여기서는 자리만 마감한다
            closeIfStillActive(repositoryId);
            MDC.remove("repositoryId");
        }
    }

    /**
     * 실행이 끝났는데 상태가 아직 「진행 중」이면 강제로 마감한다.
     *
     * <p>정상 경로에서는 {@code markSucceeded}·{@code markSkipped}·{@code markFailed} 중
     * 하나가 이미 불려 {@code isActive()} 가 거짓이므로 <b>아무 일도 하지 않는다.</b>
     * 이것이 도는 것은 위 {@code catch} 들을 빠져나간 경우뿐이다.
     */
    private void closeIfStillActive(Long repositoryId) {
        try {
            registry.stateOf(repositoryId)
                    .filter(ScanExecutionState::isActive)
                    .ifPresent(state -> {
                        log.error("스캔이 마감되지 않은 채 끝났다 repositoryId={} phase={} "
                                + "— 자리를 비운다", repositoryId, state.phase());
                        registry.markFailed(repositoryId, null, "UNTERMINATED", null);
                    });
        } catch (RuntimeException e) {
            // 안전망이 예외를 새로 만들지 않는다 — 원래 예외를 가려 버린다
            log.error("스캔 상태 마감에 실패했다 repositoryId={}", repositoryId, e);
        }
    }
}
