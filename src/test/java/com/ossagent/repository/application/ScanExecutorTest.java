package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실행부의 <b>마감 보장</b>.
 *
 * <p>🔴 이 클래스가 지키는 것은 하나다 — <b>어떤 경로로 끝나든 자리가 비워진다.</b>
 * 비워지지 않으면 그 저장소는 영구히 「진행 중」이고, 상태가 프로세스 메모리에 있어
 * <b>재기동 외에 복구 수단이 없다.</b>
 *
 * <p>{@code @Async} 밖으로 나간 예외는 사라지므로({@code void} 라 {@code Future} 도 없다)
 * 여기서 새는 것은 로그에도 안 남고 호출자도 모른다. 조용한 영구 고장이다.
 */
class ScanExecutorTest {

    private static final Long REPO = 1L;

    private final ScanPipelineUseCase pipeline = mock(ScanPipelineUseCase.class);
    private final ScanExecutionRegistry registry = new InMemoryScanExecutionRegistry(
            Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC));
    private final ScanExecutor executor = new ScanExecutor(pipeline, registry);

    @Test
    @DisplayName("정상 종료하면 SUCCEEDED 로 마감한다")
    void 정상_종료는_SUCCEEDED_다() {
        registry.tryStart(REPO);
        when(pipeline.run(REPO)).thenReturn(
                new ScanPipelineResult(3, 3, 1, 0, 0, 0, false, null, null));

        executor.execute(REPO);

        assertThat(registry.stateOf(REPO).orElseThrow().phase())
                .isEqualTo(ScanExecutionState.Phase.SUCCEEDED);
    }

    @Test
    @DisplayName("건너뛴 실행은 SKIPPED 다 — 실패로 세지 않는다")
    void 건너뛴_실행은_SKIPPED_다() {
        registry.tryStart(REPO);
        when(pipeline.run(REPO)).thenReturn(
                ScanPipelineResult.skipped(ScanTarget.SkipReason.CONTRIBUTION_FORBIDDEN));

        executor.execute(REPO);

        assertThat(registry.stateOf(REPO).orElseThrow().phase())
                .isEqualTo(ScanExecutionState.Phase.SKIPPED);
    }

    @Test
    @DisplayName("단계 실패는 단계와 타입을 남긴다 — 예외 원문은 아니다 S4")
    void 단계_실패는_타입만_남긴다_S4() {
        registry.tryStart(REPO);
        doThrow(ScanStageFailedException.at(ScanExecutionState.Stage.SCAN, REPO,
                new IllegalStateException("토큰이 섞인 메시지")))
                .when(pipeline).run(REPO);

        executor.execute(REPO);

        ScanExecutionState state = registry.stateOf(REPO).orElseThrow();
        assertThat(state.phase()).isEqualTo(ScanExecutionState.Phase.FAILED);
        assertThat(state.failureStage()).isEqualTo(ScanExecutionState.Stage.SCAN);
        assertThat(state.failureType()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("알 수 없는 RuntimeException 도 FAILED 로 마감한다")
    void 알_수_없는_실패도_마감한다() {
        registry.tryStart(REPO);
        doThrow(new IllegalArgumentException("어딘가 고장")).when(pipeline).run(REPO);

        executor.execute(REPO);

        assertThat(registry.stateOf(REPO).orElseThrow().isActive()).isFalse();
    }

    @Test
    @DisplayName("🔴 Error 가 나도 자리가 비워진다 — 안전망이 없으면 영구 RUNNING 이다")
    void Error_가_나도_자리를_비운다() {
        registry.tryStart(REPO);
        doThrow(new StackOverflowError("깊은 재귀")).when(pipeline).run(REPO);

        // Error 는 삼키지 않는다 — 올라간다. 자리만 마감한다
        try {
            executor.execute(REPO);
        } catch (StackOverflowError expected) {
            // 기대한 전파
        }

        assertThat(registry.stateOf(REPO).orElseThrow().isActive())
                .as("catch(RuntimeException) 만으로는 Error 를 못 잡는다 — "
                        + "그 경우 저장소가 영구 409 이고 재기동 외에 복구 수단이 없다")
                .isFalse();
    }

    @Test
    @DisplayName("🔴 마감 기록 자체가 실패해도 자리가 비워진다")
    void 마감_기록이_실패해도_자리를_비운다() {
        ScanExecutionRegistry flaky = mock(ScanExecutionRegistry.class);
        when(flaky.stateOf(REPO)).thenReturn(java.util.Optional.of(
                new ScanExecutionState(REPO, ScanExecutionState.Phase.RUNNING,
                        Instant.now(), null, null, null, null)));
        doThrow(new IllegalStateException("기록 실패"))
                .when(flaky).markSucceeded(any(), any());
        when(pipeline.run(REPO)).thenReturn(
                new ScanPipelineResult(0, 0, 0, 0, 0, 0, false, null, null));

        new ScanExecutor(pipeline, flaky).execute(REPO);

        // 안전망이 UNTERMINATED 로 마감을 시도한다 — 예외를 새로 던지지 않는다
        org.mockito.Mockito.verify(flaky)
                .markFailed(REPO, null, "UNTERMINATED", null);
    }
}
