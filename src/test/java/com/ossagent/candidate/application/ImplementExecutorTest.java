package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.ossagent.candidate.application.ImplementationRegistry.ImplementationPhase;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.ImplementationDeferredException;
import com.ossagent.candidate.domain.StatusTransition;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 백그라운드 구간의 마감 — {@code @Async void} 밖으로 던진 예외는 아무에게도 도달하지 않으므로
 * 여기서 잡아 레지스트리에 <b>타입만</b> 남기는지 본다 (#106 · S-4).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ImplementExecutorTest {

    private static final Long CANDIDATE = 7L;
    private static final Long REPOSITORY = 2L;

    private final ImplementCandidateUseCase implement = mock(ImplementCandidateUseCase.class);
    private final ImplementationRegistry registry =
            new ImplementationRegistry(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private final ImplementExecutor executor = new ImplementExecutor(implement, registry);

    private CandidateImplementationWriter.ImplementationStart start() {
        registry.tryStart(CANDIDATE, REPOSITORY);
        AnalyzableIssue issue = new AnalyzableIssue(1L, REPOSITORY, 42, "제목", "본문",
                List.of(), "https://example.invalid/i/42", FilterOutcome.PASSED, null);
        return new CandidateImplementationWriter.ImplementationStart(CANDIDATE, issue, 1,
                new StatusTransition(CandidateStatus.SELECTED, CandidateStatus.IMPLEMENTING));
    }

    @Test
    @DisplayName("끝나면 SUCCEEDED 로 마감하고 저장소 잠금을 푼다")
    void 정상_종료는_SUCCEEDED_다() {
        executor.execute(start());

        assertThat(registry.stateOf(CANDIDATE)).get()
                .extracting(ImplementationRegistry.ImplementationProgress::phase)
                .isEqualTo(ImplementationPhase.SUCCEEDED);
        assertThat(registry.isRepositoryBusy(REPOSITORY)).isFalse();
    }

    @Test
    @DisplayName("미룸은 DEFERRED — 실패로 세지 않는다 (#98)")
    void 미룸은_DEFERRED_다() {
        doThrow(new ImplementationDeferredException(CANDIDATE, "일시 장애로 미룬다"))
                .when(implement).continueAfterStart(org.mockito.ArgumentMatchers.any());

        executor.execute(start());

        assertThat(registry.stateOf(CANDIDATE)).get()
                .extracting(ImplementationRegistry.ImplementationProgress::phase)
                .isEqualTo(ImplementationPhase.DEFERRED);
        assertThat(registry.isRepositoryBusy(REPOSITORY)).isFalse();
    }

    @Test
    @DisplayName("🔴 실패는 타입만 남긴다 — 예외 본문은 진행 조회로 HTTP 에 나간다 S-4")
    void 실패는_타입만_남긴다_S4() {
        doThrow(new IllegalStateException("https://user:secret@example.invalid 에서 죽었다"))
                .when(implement).continueAfterStart(org.mockito.ArgumentMatchers.any());

        executor.execute(start());

        ImplementationRegistry.ImplementationProgress progress = registry.stateOf(CANDIDATE).orElseThrow();
        assertThat(progress.phase()).isEqualTo(ImplementationPhase.FAILED);
        assertThat(progress.message()).isEqualTo("IllegalStateException").doesNotContain("secret");
        assertThat(registry.isRepositoryBusy(REPOSITORY)).as("실패해도 잠금은 푼다").isFalse();
    }
}
