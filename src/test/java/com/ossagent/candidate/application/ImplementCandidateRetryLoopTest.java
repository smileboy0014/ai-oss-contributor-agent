package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ossagent.agent.domain.SandboxPermanentException;
import com.ossagent.agent.domain.SandboxTransientException;
import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.TargetWorkspaceSource;
import com.ossagent.agent.domain.WorkspaceDiff;
import com.ossagent.candidate.domain.AgentRun;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.ChangeVerifier;
import com.ossagent.candidate.domain.CodingAgent;
import com.ossagent.candidate.domain.CodingInput;
import com.ossagent.candidate.domain.DiffReviewer;
import com.ossagent.candidate.domain.FakeChangeVerifier;
import com.ossagent.candidate.domain.FakeDiffReviewer;
import com.ossagent.candidate.domain.GeneratedFile;
import com.ossagent.candidate.domain.ImplementationDeferredException;
import com.ossagent.candidate.domain.ImplementationPlan;
import com.ossagent.candidate.domain.PlannedFile;
import com.ossagent.candidate.domain.PlannedFile.ChangeKind;
import com.ossagent.candidate.domain.StatusTransition;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase;
import com.ossagent.repository.application.BuildRepositoryContextUseCase;
import com.ossagent.repository.domain.ContextBudget;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryContext;
import com.ossagent.repository.domain.RepositoryCoordinates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 재시도 루프 — #21 · S-2 · S-6.
 *
 * <h2>🔴 바퀴 수는 <b>상태로 구분되지 않는다</b></h2>
 *
 * <p>「1바퀴 돌고 실패」와 「3바퀴 돌고 실패」는 <b>둘 다 {@code FAILED}</b> 다.
 * 상태만 단언하면 루프가 아예 없어도 초록이다 — {@code testing-philosophy.md} 의
 * 「가드는 있다가 아니라 문다」가 여기 그대로 걸린다.
 *
 * <p>그래서 <b>페이크의 호출 기록</b>을 센다. {@code FakeChangeVerifier.requests()} ·
 * {@code FakeDiffReviewer.calls()} 가 유일한 증거다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ImplementCandidateRetryLoopTest {

    private static final Long CANDIDATE_ID = 7L;
    private static final String PLANNED = "src/main/java/A.java";

    /** 상한과 같아야 한다 — 다르면 이 테스트가 무엇을 재는지 모호해진다. */
    private static final int MAX_ATTEMPTS = 3;

    // ── 성공 경로 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("첫 바퀴에 통과하면 READY_FOR_PR 이고 한 바퀴만 돈다")
    void 첫_바퀴_통과(@TempDir Path root) throws Exception {
        Fixture f = fixture(root);
        f.verifier().givenInOrder(FakeChangeVerifier.passing());
        f.reviewer().givenInOrder(FakeDiffReviewer.passingReview());

        f.useCase().implement(CANDIDATE_ID);

        assertThat(f.verifier().requests()).hasSize(1);
        assertThat(f.reviewer().calls()).hasSize(1);
        assertThat(f.verifier().prepared())
                .as("워밍은 코딩 전에 한 번 — 바퀴마다가 아니다 (#99)")
                .containsExactly(CANDIDATE_ID);
        verify(f.retries()).readyForPr(CANDIDATE_ID);
        verify(f.retries(), never()).fail(anyLong(), anyInt(), any(), anyString());
    }

    /**
     * 🔴 워밍 실패는 <b>전이 앞</b>이다 (#99). 후보는 {@code SELECTED} 그대로이고 아무 바퀴도 돌지 않는다.
     * 초안은 verify 안에서 처음 워밍해 생성 코드의 컴파일 실패가 첫 바퀴 종단이 됐다.
     */
    @Test
    @DisplayName("워밍이 실패하면 전이 전에 올라간다 — 바퀴를 돌지 않고 후보는 SELECTED 그대로다")
    void 워밍_실패는_전이_앞이다(@TempDir Path root) throws Exception {
        Fixture f = fixture(root);
        f.verifier().thenFailPrepareWith(new SandboxPermanentException("워밍이 0 아닌 종료코드로 끝났다"));

        assertThatThrownBy(() -> f.useCase().implement(CANDIDATE_ID))
                .isInstanceOf(SandboxPermanentException.class);

        assertThat(f.verifier().requests()).as("검증 바퀴가 돌지 않는다").isEmpty();
        verify(f.writer(), never()).start(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(f.retries(), never()).fail(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("2바퀴째 통과하면 READY_FOR_PR 이다 — 재시도가 실제로 의미가 있다")
    void 두_번째_바퀴에_통과(@TempDir Path root) throws Exception {
        Fixture f = fixture(root);
        // 🔴 서로 다른 요약이어야 한다 — 같으면 지문 판정이 조기 중단시킨다(FR-5)
        f.verifier().givenInOrder(
                FakeChangeVerifier.testFailure("A 테스트가 깨졌다"),
                FakeChangeVerifier.passing());
        f.reviewer().givenInOrder(FakeDiffReviewer.passingReview());

        f.useCase().implement(CANDIDATE_ID);

        assertThat(f.verifier().requests())
                .as("검증이 두 번 불려야 한다 — 한 번이면 루프가 돌지 않은 것이다")
                .hasSize(2);
        verify(f.retries()).readyForPr(CANDIDATE_ID);
    }

    /** 🔴 검증이 통과해도 리뷰가 막으면 다시 돈다 — 리뷰 실패가 같은 카운터다 (Q-6 「합산」). */
    @Test
    @DisplayName("리뷰가 변경을 요구하면 같은 카운터로 다시 돈다")
    void 리뷰_실패도_같은_카운터다_S6(@TempDir Path root) throws Exception {
        Fixture f = fixture(root);
        f.verifier().givenInOrder(FakeChangeVerifier.passing());
        f.reviewer().givenInOrder(
                FakeDiffReviewer.changesRequested("널 검사가 빠졌다"),
                FakeDiffReviewer.passingReview());

        f.useCase().implement(CANDIDATE_ID);

        assertThat(f.reviewer().calls()).hasSize(2);
        assertThat(f.verifier().requests())
                .as("리뷰 재시도도 CODE 부터 다시 돈다 — 검증이 두 번 불린다")
                .hasSize(2);
        verify(f.retries()).readyForPr(CANDIDATE_ID);
    }

    // ── 상한 소진 (S-6) ─────────────────────────────────────────────────────

    /**
     * 🔴 <b>상한 소진은 {@code FAILED} 이고 그 자체가 사람에게 넘기는 신호다.</b>
     *
     * <p>바퀴 수를 함께 세지 않으면 「1바퀴 돌고 죽었다」와 구분되지 않는다.
     */
    @Test
    @DisplayName("계속 실패하면 상한만큼만 돌고 FAILED 다 — S-6")
    void 상한_소진(@TempDir Path root) throws Exception {
        Fixture f = fixture(root);
        // 바퀴마다 다른 요약 — 지문 조기 중단이 아니라 「상한」으로 끝나는 것을 본다
        f.verifier().givenInOrder(
                FakeChangeVerifier.testFailure("1번째 실패"),
                FakeChangeVerifier.testFailure("2번째 실패"),
                FakeChangeVerifier.testFailure("3번째 실패"));

        f.useCase().implement(CANDIDATE_ID);

        assertThat(f.verifier().requests())
                .as("상한(%d)을 넘겨 돌면 비용이 예산을 벗어난다", MAX_ATTEMPTS)
                .hasSize(MAX_ATTEMPTS);
        verify(f.retries(), never()).readyForPr(anyLong());
    }

    // ── 일시 장애는 미룬다 — 태우지 않는다 (#98) ─────────────────────────────

    /**
     * 🔴 이미지 없음·데몬 다운은 후보의 코드와 무관하다. 초안은 {@code Stop} 으로 보내
     * 인프라 장애 한 번이 후보를 영구히 {@code FAILED} 로 지웠다.
     *
     * <p>「{@code fail} 이 불리지 않았다」만 보면 루프가 아예 안 돌아도 초록이다 —
     * {@code defer} 가 <b>불렸는지</b>와 예외가 <b>웹 층까지 올라오는지</b>를 함께 본다.
     */
    @Test
    @DisplayName("샌드박스 일시 장애면 FAILED 가 아니라 SELECTED 로 되돌리고 503 으로 올린다 — S-6")
    void 일시_장애는_미룬다_S6(@TempDir Path root) throws Exception {
        Fixture f = fixture(root);
        f.verifier().thenFailWith(new SandboxTransientException("이미지가 로컬에 없다"));

        assertThatThrownBy(() -> f.useCase().implement(CANDIDATE_ID))
                .isInstanceOf(ImplementationDeferredException.class);

        assertThat(f.verifier().requests()).as("한 바퀴만 돈다 — 재시도가 아니다").hasSize(1);
        verify(f.retries()).defer(eq(CANDIDATE_ID), anyInt(), eq(AgentRun.Stage.VERIFY), anyString());
        verify(f.retries(), never()).fail(anyLong(), anyInt(), any(), anyString());
        verify(f.retries(), never()).readyForPr(anyLong());
    }

    // ── 판정 불가 (재시도하지 않는다) ────────────────────────────────────────

    /**
     * 🔴 {@code UNDETERMINED} 를 실패로 읽으면 <b>고칠 수 없는 것에 3바퀴</b>를 태운다.
     *
     * <p>이 테스트가 그 회귀를 고정한다 — 바퀴가 <b>하나</b>여야 한다.
     */
    @Test
    @DisplayName("판정 불가는 재시도하지 않고 한 바퀴에 끝난다 — S-6")
    void 판정_불가는_한_바퀴다_S6(@TempDir Path root) throws Exception {
        Fixture f = fixture(root);
        f.verifier().givenUndetermined();

        f.useCase().implement(CANDIDATE_ID);

        assertThat(f.verifier().requests())
                .as("판정 불가는 같은 입력에 같은 결과다 — 재시도가 예산만 태운다")
                .hasSize(1);
        verify(f.retries()).fail(eq(CANDIDATE_ID), anyInt(),
                eq(AgentRun.Stage.VERIFY), anyString());
        verify(f.retries(), never()).readyForPr(anyLong());
    }

    // ── 같은 실패 반복 (FR-5) ────────────────────────────────────────────────

    /**
     * 🔴 같은 실패가 두 번이면 <b>상한 전에</b> 멈춘다.
     *
     * <p>🕳 실물에서는 빌드 출력에 타임스탬프가 섞여 발화하지 않을 수 있다 —
     * {@code FailureFingerprint} javadoc 의 한계. 여기서는 <b>장치 자체</b>를 본다.
     */
    @Test
    @DisplayName("같은 실패가 반복되면 상한 전에 멈춘다")
    void 같은_실패_반복은_조기_중단(@TempDir Path root) throws Exception {
        Fixture f = fixture(root);
        f.verifier().givenInOrder(FakeChangeVerifier.testFailure("늘 같은 실패"));

        f.useCase().implement(CANDIDATE_ID);

        assertThat(f.verifier().requests())
                .as("2바퀴째에 같은 지문임이 드러나 멈춘다 — 상한(%d)까지 가지 않는다", MAX_ATTEMPTS)
                .hasSize(2);
        verify(f.retries(), never()).readyForPr(anyLong());
    }

    // ── 되먹임 (FR-3) ───────────────────────────────────────────────────────

    /** 다음 바퀴 프롬프트에 직전 실패가 실린다 — 안 실리면 모델이 같은 것을 또 만든다. */
    @Test
    @DisplayName("다음 바퀴 코딩 입력에 직전 실패가 되먹여진다")
    void 실패를_되먹인다(@TempDir Path root) throws Exception {
        Fixture f = fixture(root);
        f.verifier().givenInOrder(
                FakeChangeVerifier.testFailure("A 테스트가 깨졌다"),
                FakeChangeVerifier.passing());

        f.useCase().implement(CANDIDATE_ID);

        List<CodingInput> inputs = f.coder().inputs();
        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(0).hasFeedback())
                .as("첫 바퀴는 되먹일 것이 없다")
                .isFalse();
        assertThat(inputs.get(1).feedback().points())
                .as("직전 실패가 실려야 모델이 다른 것을 만든다")
                .containsExactly("A 테스트가 깨졌다");
    }

    // ── 🔴 S-2 ─────────────────────────────────────────────────────────────

    /**
     * 🔴 <b>루프는 PR 을 만들지 않는다.</b> 성공의 종착은 {@code READY_FOR_PR} 이고
     * 그 다음은 <b>세 번째 승인 게이트</b>(#23)다.
     *
     * <p>🔴 <b>여기서 「markPrCreated 를 부르지 않는다」를 증명하지 않는다.</b>
     * 런타임 테스트가 할 수 있는 것은 「이 시나리오에서 안 불렀다」까지이고,
     * 그것은 <b>다른 경로로 부르는 것</b>을 막지 못한다.
     *
     * <p>정적 단언은 {@code ApprovalGateArchitectureTest} 가 <b>여집합</b>으로 한다 —
     * 「{@code com.ossagent} 전체에서 그 메서드를 부르는 타입이 0개」. 둘은 서로를
     * 대신하지 않고, <b>이름 문자열을 훑는 검사로 갈음하지도 않는다</b>(거부목록이 된다).
     */
    @Test
    @DisplayName("🔴 통과해도 PR_CREATED 로 가지 않는다 — S-2")
    void 루프는_PR_을_만들지_않는다_S2(@TempDir Path root) throws Exception {
        Fixture f = fixture(root);
        f.verifier().givenInOrder(FakeChangeVerifier.passing());
        f.reviewer().givenInOrder(FakeDiffReviewer.passingReview());

        f.useCase().implement(CANDIDATE_ID);

        verify(f.retries()).readyForPr(CANDIDATE_ID);
    }

    // ── 픽스처 ─────────────────────────────────────────────────────────────

    private record Fixture(ImplementCandidateUseCase useCase,
            FakeChangeVerifier verifier,
            FakeDiffReviewer reviewer,
            RecordingCodingAgent coder,
            CandidateRetryWriter retries,
            CandidateImplementationWriter writer) {
    }

    private static Fixture fixture(Path root) throws Exception {
        // ⚠ @TempDir 는 macOS 에서 /var(심볼릭 링크) 아래라 정규화 전후가 다르다 —
        //   SandboxWorkspace 가 toRealPath 로 「루트 하위인가」를 단언하므로(S-3)
        //   루트도 정규화한 것을 넘긴다
        Path real = root.toRealPath();
        Path dir = real.resolve("ws");
        Files.createDirectories(dir);
        SandboxWorkspace workspace = SandboxWorkspace.under(dir, real);

        ImplementationPlan plan = new ImplementationPlan(
                List.of(new PlannedFile(PLANNED, ChangeKind.MODIFY, "고친다")),
                "요약", "테스트 전략", 10);

        AnalyzableIssue issue = new AnalyzableIssue(1L, 2L, 42, "제목", "본문",
                List.of(), "https://example.invalid/i/42", FilterOutcome.PASSED, null);

        CandidateImplementationWriter writer = mock(CandidateImplementationWriter.class);
        given(writer.admit(eq(CANDIDATE_ID), eq(true))).willReturn(
                new CandidateImplementationWriter.Admission(CANDIDATE_ID, issue));
        given(writer.start(eq(CANDIDATE_ID), eq(true))).willReturn(
                new CandidateImplementationWriter.ImplementationStart(CANDIDATE_ID, issue, 1,
                        new StatusTransition(CandidateStatus.SELECTED,
                                CandidateStatus.IMPLEMENTING)));
        given(writer.recordChange(anyLong(), anyString(), anyString())).willReturn(99L);

        PlanImplementationUseCase planner = mock(PlanImplementationUseCase.class);
        given(planner.plan(CANDIDATE_ID)).willReturn(plan);

        BuildRepositoryContextUseCase contexts = mock(BuildRepositoryContextUseCase.class);
        given(contexts.build(any())).willReturn(context());

        AnalyzeRepositoryPolicyUseCase policies = mock(AnalyzeRepositoryPolicyUseCase.class);
        given(policies.coordinatesOf(2L)).willReturn(coordinates());
        given(policies.constraintsOf(2L)).willReturn(ContributionConstraints.unknown());

        // 🔴 상한 소진을 실제 도메인 규칙으로 재현한다 — mock 이 「몇 바퀴째냐」를
        //    임의로 답하면 이 테스트가 재는 것이 루프가 아니라 mock 이 된다
        CandidateRetryWriter retries = mock(CandidateRetryWriter.class);
        int[] attempt = {1};
        given(retries.retry(eq(CANDIDATE_ID), anyString())).willAnswer(invocation -> {
            if (attempt[0] >= MAX_ATTEMPTS) {
                return new CandidateRetryWriter.RetryOutcome(
                        new StatusTransition(CandidateStatus.TESTING, CandidateStatus.FAILED),
                        attempt[0], true);
            }
            attempt[0]++;
            return new CandidateRetryWriter.RetryOutcome(
                    new StatusTransition(CandidateStatus.TESTING, CandidateStatus.IMPLEMENTING),
                    attempt[0], false);
        });

        FakeChangeVerifier verifier = new FakeChangeVerifier();
        verifier.reset();
        FakeDiffReviewer reviewer = new FakeDiffReviewer();
        reviewer.reset();
        RecordingCodingAgent coder = new RecordingCodingAgent();

        ImplementCandidateUseCase useCase = new ImplementCandidateUseCase(writer, planner,
                contexts, policies, provider(new FixedWorkspaceSource(workspace)),
                provider(coder), provider(verifier), provider(reviewer), retries,
                new ExecutionProperties(MAX_ATTEMPTS, 1800),
                new com.ossagent.support.observability.PipelineMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));

        return new Fixture(useCase, verifier, reviewer, coder, retries, writer);
    }

    private static RepositoryCoordinates coordinates() {
        return new RepositoryCoordinates("spring-projects", "spring-kafka");
    }

    private static RepositoryContext context() {
        return new RepositoryContext(coordinates(), "main", "sha", List.of(),
                ContextBudget.of(12, 100_000, 20_000), false, 0, Map.of());
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    // ── 대역 ────────────────────────────────────────────────────────────────

    /** 🔴 바퀴마다 받은 입력을 모은다 — 되먹임이 실렸는지의 유일한 증거다. */
    private static final class RecordingCodingAgent implements CodingAgent {

        private final List<CodingInput> inputs = new ArrayList<>();

        List<CodingInput> inputs() {
            return List.copyOf(inputs);
        }

        @Override
        public List<GeneratedFile> write(Long candidateId, int attempt, CodingInput input) {
            inputs.add(input);
            return List.of(new GeneratedFile(PLANNED, "class A {}"));
        }
    }

    /** 워크스페이스는 바퀴마다 같다 — 루프가 clone 을 반복하지 않는 것도 여기서 드러난다. */
    private static final class FixedWorkspaceSource implements TargetWorkspaceSource {

        private final SandboxWorkspace workspace;

        private FixedWorkspaceSource(SandboxWorkspace workspace) {
            this.workspace = workspace;
        }

        @Override
        public SandboxWorkspace fetch(RepositoryCoordinates coordinates, String branch) {
            return workspace;
        }

        @Override
        public WorkspaceDiff diff(SandboxWorkspace workspace) {
            return new WorkspaceDiff("--- a\n+++ b\n", Set.of(PLANNED));
        }

        @Override
        public Set<String> apply(SandboxWorkspace workspace, String unifiedDiff) {
            throw new UnsupportedOperationException("착수 경로는 diff 를 입히지 않는다 — PR 게이트의 일이다");
        }
    }
}
