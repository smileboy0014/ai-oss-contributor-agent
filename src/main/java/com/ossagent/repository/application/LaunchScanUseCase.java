package com.ossagent.repository.application;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryNotFoundException;
import com.ossagent.repository.domain.RepositoryNotScannableException;
import com.ossagent.repository.domain.ScanAlreadyRunningException;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 스캔을 <b>비동기로</b> 기동한다 — #14 NFR-1.
 *
 * <h2>🔴 자리 잡기와 제출은 한 덩어리다</h2>
 *
 * <p>{@code tryStart} 로 자리를 잡은 뒤 제출이 거절되면 <b>반드시 되돌린다.</b>
 * 거절({@code RejectedExecutionException})은 <b>호출 스레드에서</b> 나므로 비동기 메서드
 * 안의 {@code finally} 로는 잡히지 않는다. 되돌리지 않으면 그 저장소가 영구히
 * 「진행 중」이 되고, 상태가 메모리에 있어 <b>재기동 외에 복구 수단이 없다.</b>
 *
 * <h2>⚠️ {@code @Async} 는 self-invocation 이면 동작하지 않는다</h2>
 *
 * <p>실제 실행부를 {@link ScanExecutor} 라는 <b>별도 빈</b>으로 가른 이유가 그것이다.
 * 같은 클래스의 메서드를 {@code this} 로 부르면 {@code @Async} 가 <b>조용히 무시되고
 * 동기 실행</b>된다 — 증상이 「API 가 느리다」뿐이라 놓치기 쉽다.
 */
@Service
public class LaunchScanUseCase {

    private static final Logger log = LoggerFactory.getLogger(LaunchScanUseCase.class);

    private final ScanExecutionRegistry registry;
    private final ScanExecutor executor;
    private final OssRepositoryRepository repositories;

    public LaunchScanUseCase(ScanExecutionRegistry registry, ScanExecutor executor,
            OssRepositoryRepository repositories) {
        this.registry = registry;
        this.executor = executor;
        this.repositories = repositories;
    }

    /**
     * 즉시 반환한다. 파이프라인은 전용 풀에서 돈다.
     *
     * <p>🔴 <b>자리를 잡기 전에 검증한다.</b> 순서가 뒤집히면 없는 저장소에도 큐 자리를
     * 잡고 작업을 제출하게 된다 — 호출자는 404 를 받는데 비동기 작업은 돌아
     * <b>동시 1건 슬롯을 태우고</b> 「404 인데 스캔 이력이 남는」 상태를 만든다.
     *
     * @throws RepositoryNotFoundException      없는 저장소 → 404
     * @throws RepositoryNotScannableException  {@code enabled = false} → 409
     * @throws ScanAlreadyRunningException      이미 진행 중이거나 큐가 찼다 → 409
     */
    public void launch(Long repositoryId) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수다");
        }
        assertScannable(repositoryId);
        if (!registry.tryStart(repositoryId)) {
            throw new ScanAlreadyRunningException(repositoryId,
                    ScanAlreadyRunningException.Reason.ALREADY_RUNNING);
        }
        try {
            executor.execute(repositoryId);
        } catch (RejectedExecutionException e) {
            // 🔴 자리를 되돌린다. 안 하면 이후 모든 요청이 409 로 막히고 재기동해야 풀린다
            registry.release(repositoryId);
            log.warn("스캔 큐가 찼다 repositoryId={} — 거절한다", repositoryId);
            throw new ScanAlreadyRunningException(repositoryId,
                    ScanAlreadyRunningException.Reason.QUEUE_FULL);
        } catch (RuntimeException e) {
            // 제출 자체가 실패한 다른 경우도 자리를 남기지 않는다
            registry.release(repositoryId);
            throw e;
        }
    }

    /**
     * ⚠️ 스케줄러는 원래 {@code enabled} 를 보고 건너뛰는데 API 경로만 보지 않고 있었다.
     * 두 진입점이 다르게 동작하면 「비활성화했는데 돈다」가 된다.
     */
    private void assertScannable(Long repositoryId) {
        OssRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
        if (!repository.isEnabled()) {
            throw new RepositoryNotScannableException(repositoryId);
        }
    }
}
