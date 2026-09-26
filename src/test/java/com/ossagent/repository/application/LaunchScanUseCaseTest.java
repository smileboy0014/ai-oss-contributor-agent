package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ossagent.repository.domain.ScanAlreadyRunningException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기동과 <b>중복 방어</b>(FR-4).
 *
 * <p>🔴 이 클래스의 핵심은 <b>레지스트리 누수</b>다. 자리를 잡은 뒤 제출이 거절됐는데
 * 되돌리지 않으면 그 저장소가 영구히 「진행 중」이 되고, 상태가 메모리에 있어
 * <b>재기동 외에 복구 수단이 없다.</b>
 */
class LaunchScanUseCaseTest {

    private static final Long REPO = 1L;

    private final ScanExecutionRegistry registry = new InMemoryScanExecutionRegistry(
            Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC));
    private final ScanExecutor executor = mock(ScanExecutor.class);
    private final LaunchScanUseCase launchScan = new LaunchScanUseCase(registry, executor);

    @Test
    @DisplayName("기동하면 실행기에 제출하고 즉시 반환한다")
    void 기동하면_제출한다() {
        launchScan.launch(REPO);

        verify(executor).execute(REPO);
        assertThat(registry.stateOf(REPO).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("이미 진행 중이면 409 — 두 번째는 제출하지 않는다 FR-4")
    void 진행_중이면_거절한다() {
        launchScan.launch(REPO);

        assertThatThrownBy(() -> launchScan.launch(REPO))
                .isInstanceOfSatisfying(ScanAlreadyRunningException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ScanAlreadyRunningException.Reason.ALREADY_RUNNING));

        // 제출은 딱 한 번이다 — 두 번 돌면 레이트리밋·토큰을 두 배로 태운다
        verify(executor).execute(REPO);
    }

    @Test
    @DisplayName("🔴 큐가 차서 거절되면 다음 요청이 다시 받아들여진다 — 누수 회귀")
    void 큐가_차서_거절되면_자리를_되돌린다() {
        doThrow(new RejectedExecutionException("queue full")).when(executor).execute(any());

        assertThatThrownBy(() -> launchScan.launch(REPO))
                .isInstanceOfSatisfying(ScanAlreadyRunningException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ScanAlreadyRunningException.Reason.QUEUE_FULL));

        assertThat(registry.stateOf(REPO))
                .as("자리를 남기면 그 저장소가 영구 409 이고 재기동해야 풀린다")
                .isEmpty();
    }

    @Test
    @DisplayName("제출이 다른 이유로 실패해도 자리를 남기지 않는다")
    void 제출_실패는_자리를_남기지_않는다() {
        doThrow(new IllegalStateException("실행기 고장")).when(executor).execute(any());

        assertThatThrownBy(() -> launchScan.launch(REPO))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.stateOf(REPO)).isEmpty();
    }

    @Test
    @DisplayName("저장소 식별자는 필수다")
    void 식별자가_없으면_거부한다() {
        assertThatThrownBy(() -> launchScan.launch(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
