package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryNotFoundException;
import com.ossagent.repository.domain.RepositoryNotScannableException;
import com.ossagent.repository.domain.ScanAlreadyRunningException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
    private final OssRepositoryRepository repositories = mock(OssRepositoryRepository.class);
    private final LaunchScanUseCase launchScan =
            new LaunchScanUseCase(registry, executor, repositories);

    @BeforeEach
    void givenRegisteredRepository() {
        when(repositories.findById(REPO)).thenReturn(Optional.of(
                new OssRepository("spring-projects", "spring-kafka",
                        "https://github.com/spring-projects/spring-kafka")));
    }

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
    @DisplayName("🔴 없는 저장소에는 자리를 잡지 않는다 — 슬롯을 태우지 않는다")
    void 없는_저장소는_자리를_잡지_않는다() {
        when(repositories.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> launchScan.launch(404L))
                .isInstanceOf(RepositoryNotFoundException.class);

        assertThat(registry.stateOf(404L))
                .as("검증을 뒤에 두면 404 를 돌려주면서 비동기 작업은 돌아 동시 1건 슬롯을 태운다")
                .isEmpty();
        verify(executor, never()).execute(any());
    }

    @Test
    @DisplayName("비활성 저장소는 409 — 스케줄러와 같은 판정이다")
    void 비활성_저장소는_기동하지_않는다() {
        // OssRepository 에 disable() 이 없다 — 호출자 없는 메서드를 도메인에 만들지 않는다.
        // enabled 를 끄는 경로(#26 의 저장소별 주기 설정 등)가 생기면 그때 만든다
        OssRepository disabled = mock(OssRepository.class);
        when(disabled.isEnabled()).thenReturn(false);
        when(repositories.findById(REPO)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> launchScan.launch(REPO))
                .as("스케줄러만 enabled 를 보면 「비활성화했는데 돈다」가 된다")
                .isInstanceOf(RepositoryNotScannableException.class);

        assertThat(registry.stateOf(REPO)).isEmpty();
        verify(executor, never()).execute(any());
    }

    @Test
    @DisplayName("저장소 식별자는 필수다")
    void 식별자가_없으면_거부한다() {
        assertThatThrownBy(() -> launchScan.launch(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
