package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ossagent.candidate.application.ImplementationRegistry.ImplementationPhase;
import com.ossagent.candidate.domain.AgentRun;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.ImplementationAlreadyRunningException;
import com.ossagent.candidate.domain.StatusTransition;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 착수 게이트 — 동기 전이 · 비동기 실행 · 저장소 잠금 (#106 · S-6).
 *
 * <p>「202 를 돌려준다」만 보면 아무것도 제출하지 않아도 초록이다. 여기서 보는 것은
 * <b>전이가 이 스레드에서 일어났는가</b>와 <b>실행기에 넘겼는가</b>, 그리고 <b>겹침이 막히는가</b>다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LaunchImplementationUseCaseTest {

    private static final Long CANDIDATE = 7L;
    private static final Long REPOSITORY = 2L;
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private final CandidateImplementationWriter writer = mock(CandidateImplementationWriter.class);
    private final ImplementCandidateUseCase implement = mock(ImplementCandidateUseCase.class);
    private final ImplementExecutor executor = mock(ImplementExecutor.class);
    private final CandidateRetryWriter retries = mock(CandidateRetryWriter.class);
    private final ImplementationRegistry registry = new ImplementationRegistry(CLOCK);
    private final LaunchImplementationUseCase useCase =
            new LaunchImplementationUseCase(writer, implement, executor, registry, retries);

    private final AnalyzableIssue issue = new AnalyzableIssue(1L, REPOSITORY, 42, "제목", "본문",
            List.of(), "https://example.invalid/i/42", FilterOutcome.PASSED, null);
    private final CandidateImplementationWriter.ImplementationStart start =
            new CandidateImplementationWriter.ImplementationStart(CANDIDATE, issue, 1,
                    new StatusTransition(CandidateStatus.SELECTED, CandidateStatus.IMPLEMENTING));

    @BeforeEach
    void setUp() {
        given(implement.executorReady()).willReturn(true);
        given(writer.admit(CANDIDATE, true))
                .willReturn(new CandidateImplementationWriter.Admission(CANDIDATE, issue));
        given(writer.start(CANDIDATE, true)).willReturn(start);
    }

    @Test
    @DisplayName("전이는 이 스레드에서 끝내고 루프는 실행기에 넘긴다 — 응답이 낡지 않는다")
    void 전이는_동기_루프는_비동기다_S6() {
        StatusTransition transition = useCase.launch(CANDIDATE);

        assertThat(transition.to()).isEqualTo(CandidateStatus.IMPLEMENTING);
        verify(writer).start(CANDIDATE, true);
        verify(executor).execute(start);
        assertThat(registry.stateOf(CANDIDATE)).get()
                .extracting(ImplementationRegistry.ImplementationProgress::phase)
                .isEqualTo(ImplementationPhase.QUEUED);
    }

    @Test
    @DisplayName("🔴 같은 저장소를 다른 후보가 쥐고 있으면 409 이고 전이하지 않는다 — 워크스페이스는 저장소당 하나다")
    void 같은_저장소는_겹쳐_돌지_않는다() {
        assertThat(registry.tryStart(99L, REPOSITORY)).isTrue();

        assertThatThrownBy(() -> useCase.launch(CANDIDATE))
                .isInstanceOfSatisfying(ImplementationAlreadyRunningException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ImplementationAlreadyRunningException.Reason.REPOSITORY_BUSY));
        verify(writer, never()).start(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(executor, never()).execute(any());
    }

    @Test
    @DisplayName("같은 후보를 두 번 누르면 409 — 승인은 한 번 일어난 사건이다")
    void 두_번_누르면_409_다_S6() {
        useCase.launch(CANDIDATE);

        assertThatThrownBy(() -> useCase.launch(CANDIDATE))
                .isInstanceOfSatisfying(ImplementationAlreadyRunningException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ImplementationAlreadyRunningException.Reason.ALREADY_RUNNING));
    }

    /** 🔴 전이는 이미 커밋됐다. 큐가 거절하면 IMPLEMENTING 에 갇히지 않게 미룬다 (#98). */
    @Test
    @DisplayName("큐가 차면 후보를 SELECTED 로 되돌리고 자리를 비운다")
    void 큐가_차면_미루고_자리를_비운다() {
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());

        assertThatThrownBy(() -> useCase.launch(CANDIDATE))
                .isInstanceOfSatisfying(ImplementationAlreadyRunningException.class, e ->
                        assertThat(e.reason())
                                .isEqualTo(ImplementationAlreadyRunningException.Reason.QUEUE_FULL));

        verify(retries).defer(eq(CANDIDATE), anyInt(), eq(AgentRun.Stage.CODE), anyString());
        assertThat(registry.isRepositoryBusy(REPOSITORY)).as("자리를 남기면 저장소가 영구히 잠긴다").isFalse();
    }

    @Test
    @DisplayName("전이가 거절되면(정책이 그 사이 닫힘) 자리를 남기지 않는다")
    void 전이_거절은_자리를_남기지_않는다_S5() {
        given(writer.start(CANDIDATE, true))
                .willThrow(new IllegalStateException("정책이 그 사이 닫혔다"));

        assertThatThrownBy(() -> useCase.launch(CANDIDATE))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.isRepositoryBusy(REPOSITORY)).isFalse();
        verify(executor, never()).execute(any());
    }
}
