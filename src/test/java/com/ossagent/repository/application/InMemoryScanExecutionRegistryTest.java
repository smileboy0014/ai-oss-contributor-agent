package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 중복 방어(FR-4)와 진행 상태의 불변식.
 *
 * <p>🔴 <b>이 클래스가 지키는 것은 「같은 저장소를 두 번 스캔하지 않는다」</b>이다.
 * 깨지면 같은 이슈에 후보가 두 번 생길 수 있고(#11 의 {@code UNIQUE} 가 막긴 하지만),
 * 무엇보다 GitHub 레이트리밋과 LLM 토큰을 두 배로 태운다.
 */
class InMemoryScanExecutionRegistryTest {

    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");
    private static final Long REPO = 1L;

    /** 🔴 <b>고정 시계다.</b> 「내가 잡았는가」를 시각 비교로 판정하면 여기서 깨진다. */
    private final ScanExecutionRegistry registry =
            new InMemoryScanExecutionRegistry(Clock.fixed(T0, ZoneOffset.UTC));

    @Test
    @DisplayName("처음에는 IDLE 이고 기록이 없다")
    void 처음에는_상태가_없다() {
        assertThat(registry.stateOf(REPO)).isEmpty();
        assertThat(ScanExecutionState.idle(REPO).phase())
                .isEqualTo(ScanExecutionState.Phase.IDLE);
    }

    @Test
    @DisplayName("🔴 진행 중이면 두 번째 시작을 거절한다 — FR-4")
    void 진행_중이면_거절한다() {
        assertThat(registry.tryStart(REPO)).isTrue();

        assertThat(registry.tryStart(REPO))
                .as("같은 저장소를 두 번 스캔하면 레이트리밋과 토큰을 두 배로 태운다")
                .isFalse();
    }

    @Test
    @DisplayName("QUEUED 와 RUNNING 을 가른다 — 동시 1건을 사람이 확인하는 유일한 창")
    void 큐와_실행을_가른다() {
        registry.tryStart(REPO);
        assertThat(registry.stateOf(REPO).orElseThrow().phase())
                .isEqualTo(ScanExecutionState.Phase.QUEUED);

        registry.markRunning(REPO);
        assertThat(registry.stateOf(REPO).orElseThrow().phase())
                .isEqualTo(ScanExecutionState.Phase.RUNNING);
    }

    @Test
    @DisplayName("🔴 release 하면 다시 받아들인다 — 큐 거절 시 누수 방지")
    void release_하면_다시_시작할_수_있다() {
        registry.tryStart(REPO);
        registry.release(REPO);

        assertThat(registry.tryStart(REPO))
                .as("되돌리지 않으면 그 저장소가 영구 409 이고 재기동 외에 복구 수단이 없다")
                .isTrue();
    }

    @Test
    @DisplayName("끝나면 다시 시작할 수 있다")
    void 끝나면_재시작_가능하다() {
        registry.tryStart(REPO);
        registry.markRunning(REPO);
        registry.markSucceeded(REPO, ScanPipelineResult.skipped(
                ScanTarget.SkipReason.POLICY_UNAVAILABLE));

        assertThat(registry.stateOf(REPO).orElseThrow().isActive()).isFalse();
        assertThat(registry.tryStart(REPO)).isTrue();
    }

    @Test
    @DisplayName("저장소마다 독립이다")
    void 저장소별로_독립이다() {
        assertThat(registry.tryStart(1L)).isTrue();

        assertThat(registry.tryStart(2L))
                .as("한 저장소가 돈다고 다른 저장소를 막으면 스케줄러 순회가 멈춘다")
                .isTrue();
    }

    @Test
    @DisplayName("실패는 단계와 타입만 남긴다 — 예외 원문 없음 S4")
    void 실패는_타입만_남긴다_S4() {
        registry.tryStart(REPO);
        registry.markFailed(REPO, ScanExecutionState.Stage.SCAN, "GitHubApiException", null);

        ScanExecutionState state = registry.stateOf(REPO).orElseThrow();
        assertThat(state.phase()).isEqualTo(ScanExecutionState.Phase.FAILED);
        assertThat(state.failureStage()).isEqualTo(ScanExecutionState.Stage.SCAN);
        assertThat(state.failureType()).isEqualTo("GitHubApiException");
    }

    @Test
    @DisplayName("🔴 동시에 열 개가 달려들어도 하나만 잡는다")
    void 경합해도_하나만_잡는다() throws Exception {
        int threads = 10;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Boolean>> tasks = IntStream.range(0, threads)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        barrier.await();
                        return registry.tryStart(REPO);
                    })
                    .toList();

            long acquired = pool.invokeAll(tasks).stream()
                    .map(InMemoryScanExecutionRegistryTest::get)
                    .filter(Boolean::booleanValue)
                    .count();

            assertThat(acquired)
                    .as("검사와 기록이 원자적이지 않으면 둘 이상이 그 사이를 통과한다")
                    .isEqualTo(1);
        }
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
