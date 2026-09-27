package com.ossagent.repository.adapter.in.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ossagent.repository.application.LaunchScanUseCase;
import com.ossagent.repository.application.RegisterRepositoryUseCase;
import com.ossagent.repository.application.ScanProperties;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.ScanAlreadyRunningException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>주기가 된 저장소만 기동한다</b> — #26 FR-2.
 *
 * <h2>여기서 보는 것과 보지 않는 것</h2>
 *
 * <p>보는 것은 「<b>누구를 부르는가</b>」다. 「주기 판정이 옳은가」는 엔티티의 몫이고
 * {@code OssRepositoryScanIntervalTest} 가 고정 시각으로 본다 — 둘을 한 테스트에 합치면
 * 판정이 틀렸는지 스케줄러가 안 물어봤는지 구분되지 않는다.
 *
 * <p>「얼마나 자주 훑는가」({@code fixed-delay}·{@code enabled})는 또 다른 축이고
 * {@code ScanSchedulerWiringTest} 가 본다.
 *
 * <p>⚠️ 엔티티를 Mockito 로 스텁한다. 식별자는 {@code @GeneratedValue} 라 공개 경로가 없고,
 * {@code ReflectionTestUtils} 로 필드를 찌르는 것은 「구현 내부 필드에 의존」 금지에 걸린다 —
 * {@code FindCandidatesUseCaseTest} 가 같은 이유로 같은 선택을 했다.
 */
class ScanSchedulerDueFilterTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);

    private final RegisterRepositoryUseCase repositories = mock(RegisterRepositoryUseCase.class);
    private final LaunchScanUseCase launchScan = mock(LaunchScanUseCase.class);
    private final ScanScheduler scheduler = new ScanScheduler(
            repositories, launchScan, ScanProperties.defaults(), CLOCK);

    @Test
    @DisplayName("🔴 주기가 안 된 저장소는 기동하지 않는다 — 전역 주기 하나면 FR-1 이 표현되지 않는다")
    void 주기가_안_된_저장소는_기동하지_않는다() {
        OssRepository notDue = repository(1L, true, false);
        when(repositories.findAll()).thenReturn(List.of(notDue));

        scheduler.scanAll();

        verify(launchScan, never()).launch(anyLong());
    }

    @Test
    @DisplayName("주기가 된 저장소는 기동한다 — 필터가 전부를 걸러 버리지 않는다")
    void 주기가_된_저장소는_기동한다() {
        OssRepository due = repository(2L, true, true);
        when(repositories.findAll()).thenReturn(List.of(due));

        scheduler.scanAll();

        verify(launchScan).launch(2L);
    }

    @Test
    @DisplayName("주기가 된 것과 안 된 것이 섞여도 된 것만 고른다")
    void 섞여_있으면_된_것만_고른다() {
        OssRepository notDue = repository(3L, true, false);
        OssRepository due = repository(4L, true, true);
        when(repositories.findAll()).thenReturn(List.of(notDue, due));

        scheduler.scanAll();

        verify(launchScan, never()).launch(3L);
        verify(launchScan).launch(4L);
    }

    @Test
    @DisplayName("🔴 주기 필터가 enabled 를 대체하지 않는다 — 비활성은 주기가 돼도 안 돈다")
    void 비활성_저장소는_주기가_돼도_안_돈다() {
        // 🔴 isDueForScan 을 true 로 둔다. 둘 중 하나만 보게 바뀌면 여기가 빨개진다
        OssRepository disabled = repository(5L, false, true);
        when(repositories.findAll()).thenReturn(List.of(disabled));

        scheduler.scanAll();

        verify(launchScan, never()).launch(anyLong());
    }

    @Test
    @DisplayName("🔴 한 저장소의 실패가 순회를 멈추지 않는다 — 주기 필터를 넣어도 그대로다")
    void 한_저장소의_실패가_순회를_멈추지_않는다() {
        OssRepository broken = repository(6L, true, true);
        OssRepository healthy = repository(7L, true, true);
        when(repositories.findAll()).thenReturn(List.of(broken, healthy));
        doThrow(new IllegalStateException("고장")).when(launchScan).launch(6L);

        scheduler.scanAll();

        verify(launchScan, times(1)).launch(7L);
    }

    @Test
    @DisplayName("이미 진행 중이면 건너뛴다 — 다음 주기가 이어받는다")
    void 이미_진행_중이면_건너뛴다() {
        OssRepository running = repository(8L, true, true);
        when(repositories.findAll()).thenReturn(List.of(running));
        doThrow(new ScanAlreadyRunningException(8L,
                ScanAlreadyRunningException.Reason.ALREADY_RUNNING))
                .when(launchScan).launch(8L);

        scheduler.scanAll();

        verify(launchScan).launch(8L);
    }

    /**
     * ⚠️ <b>{@code when(findAll())} 의 인자 안에서 부르지 않는다.</b> 스텁 안에서 스텁하면
     * Mockito 가 {@code UnfinishedStubbingException} 을 던진다 — 실제로 한 번 당했다.
     * 먼저 만들고 그다음 스텁한다.
     */
    private OssRepository repository(Long id, boolean enabled, boolean due) {
        OssRepository repository = mock(OssRepository.class);
        when(repository.getId()).thenReturn(id);
        when(repository.isEnabled()).thenReturn(enabled);
        when(repository.isDueForScan(any(), any())).thenReturn(due);
        return repository;
    }
}
