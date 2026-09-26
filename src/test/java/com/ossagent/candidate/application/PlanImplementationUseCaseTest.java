package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.FakeImplementationPlanner;
import com.ossagent.candidate.domain.ImplementationPlan;
import com.ossagent.candidate.domain.IssueAnalysis;
import com.ossagent.candidate.domain.PlanRejectedException;
import com.ossagent.candidate.domain.PlannedFile;
import com.ossagent.candidate.domain.PlanningInput;
import com.ossagent.issue.application.FindAnalyzableIssuesUseCase;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase;
import com.ossagent.repository.application.BuildRepositoryContextUseCase;
import com.ossagent.repository.domain.ContextBudget;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryContext;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.SelectedFile;
import com.ossagent.repository.domain.SelectionReason;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 계획 수립의 업무 흐름 — 이슈 #16.
 *
 * <p>이 클래스가 고정하는 것은 <b>실패 경로</b>다. 성공은 한 줄이고, 이 이슈의 가치는
 * 거부·재생성·상한 소진에 있다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanImplementationUseCaseTest {

    private static final long CANDIDATE_ID = 7L;
    private static final long ISSUE_ID = 11L;
    private static final long REPOSITORY_ID = 3L;
    private static final String SHOWN = "src/main/java/org/x/Poller.java";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC);

    private ContributionCandidateRepository candidates;
    private CandidatePlanningWriter writer;
    private FindAnalyzableIssuesUseCase issues;
    private BuildRepositoryContextUseCase contexts;
    private AnalyzeRepositoryPolicyUseCase policies;
    private FakeImplementationPlanner planner;
    private ContributionCandidate candidate;

    @BeforeEach
    void setUp() {
        candidate = selectedCandidate();

        // 🔴 실제 Writer 를 쓴다 — mock 으로 대체하면 「전이가 저장되는가」를 못 본다.
        //    repository 만 mock 이고, saveAndFlush 가 불렸는지로 영속을 단언한다
        candidates = mock(ContributionCandidateRepository.class);
        given(candidates.findById(CANDIDATE_ID)).willReturn(Optional.of(candidate));
        given(candidates.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> invocation.getArgument(0));
        writer = new CandidatePlanningWriter(candidates, CLOCK);

        issues = mock(FindAnalyzableIssuesUseCase.class);
        given(issues.findOne(ISSUE_ID)).willReturn(Optional.of(issue()));

        contexts = mock(BuildRepositoryContextUseCase.class);
        given(contexts.build(org.mockito.ArgumentMatchers.any())).willReturn(context(false));

        policies = mock(AnalyzeRepositoryPolicyUseCase.class);
        given(policies.constraintsOf(REPOSITORY_ID)).willReturn(ContributionConstraints.unknown());

        planner = new FakeImplementationPlanner();
    }

    private PlanImplementationUseCase useCase() {
        return useCaseWith(ImplementationPlanProperties.defaults());
    }

    private PlanImplementationUseCase useCaseWith(ImplementationPlanProperties properties) {
        return new PlanImplementationUseCase(writer, issues, contexts, policies, planner,
                properties);
    }

    // ── 성공 ─────────────────────────────────────────────────────────────────

    @Test
    void 검증을_통과한_계획을_한_번에_돌려준다() {
        planner.willReturn(goodPlan());

        ImplementationPlan plan = useCase().plan(CANDIDATE_ID);

        assertThat(plan.paths()).containsExactly(SHOWN);
        assertThat(planner.callCount()).isEqualTo(1);
    }

    @Test
    void 성공해도_상태를_전이하지_않는다() {
        planner.willReturn(goodPlan());

        useCase().plan(CANDIDATE_ID);

        assertThat(candidate.getStatus())
                .as("계획은 IMPLEMENTING 전이 「앞」이다 — startImplementing 은 계획을 받은 쪽이 부른다")
                .isEqualTo(CandidateStatus.SELECTED);
        assertThat(candidate.getAttempt())
                .as("PLAN 시점의 candidate.attempt 는 0 이어야 한다 — 기존 불변식")
                .isZero();
    }

    // ── 재생성 (FR-5) ────────────────────────────────────────────────────────

    @Test
    void 검증에_걸리면_사유를_실어_다시_세운다() {
        planner.willReturn(planFor("src/main/java/org/x/Ghost.java")).willReturn(goodPlan());

        ImplementationPlan plan = useCase().plan(CANDIDATE_ID);

        assertThat(plan.paths()).containsExactly(SHOWN);
        assertThat(planner.callCount()).isEqualTo(2);

        PlanningInput retry = planner.received().get(1);
        assertThat(retry.isRetry())
                .as("사유를 싣지 않으면 재생성이 같은 실수를 반복하고 예산만 태운다")
                .isTrue();
        assertThat(retry.feedback().asFeedback()).contains("Ghost.java");
    }

    @Test
    void 스키마_위반도_재생성_대상이다() {
        planner.willThrow(new PlanRejectedException("JSON 아님")).willReturn(goodPlan());

        ImplementationPlan plan = useCase().plan(CANDIDATE_ID);

        assertThat(plan.paths()).containsExactly(SHOWN);
        assertThat(planner.callCount())
                .as("모델이 JSON 을 한 번 잘못 냈다고 후보를 종단으로 보내지 않는다")
                .isEqualTo(2);
    }

    // ── 상한 소진 (S-6 · D-4) ────────────────────────────────────────────────

    @Test
    void 상한을_소진하면_후보가_FAILED_가_된다_S6() {
        planner.willReturn(planFor("src/main/java/org/x/Ghost.java"))
                .willReturn(planFor("src/main/java/org/x/Ghost2.java"));

        assertThatThrownBy(() -> useCase().plan(CANDIDATE_ID))
                .isInstanceOf(PlanExhaustedException.class);

        assertThat(candidate.getStatus())
                .as("상한 소진이 FAILED 로 못 가면 후보가 SELECTED 에 박혀 사람이 볼 신호가 없다")
                .isEqualTo(CandidateStatus.FAILED);

        // 🔴 메모리 상태만 보면 트랜잭션이 안 걸려도 통과한다. 저장까지 단언한다 —
        //    초안은 UseCase 안에 @Transactional 을 두어 self-invocation 으로 조용히 새고 있었다
        org.mockito.Mockito.verify(candidates).saveAndFlush(candidate);
    }

    @Test
    void 상한을_넘겨_호출하지_않는다() {
        planner.willReturn(planFor("src/main/java/org/x/Ghost.java"))
                .willReturn(planFor("src/main/java/org/x/Ghost2.java"));

        assertThatThrownBy(() -> useCase().plan(CANDIDATE_ID))
                .isInstanceOf(PlanExhaustedException.class);

        assertThat(planner.callCount())
                .as("3회째 호출이 있으면 곱셈 예산이 무너진다")
                .isEqualTo(2);
    }

    @Test
    void 상한이_1이면_재생성하지_않는다() {
        planner.willReturn(planFor("src/main/java/org/x/Ghost.java"));

        assertThatThrownBy(() -> useCaseWith(
                new ImplementationPlanProperties(1, 4000, 8, 400, 2.0)).plan(CANDIDATE_ID))
                .isInstanceOf(PlanExhaustedException.class);

        assertThat(planner.callCount()).isEqualTo(1);
    }

    @Test
    void 소진_예외는_사유를_들고_나간다() {
        planner.willReturn(planFor("src/main/java/org/x/Ghost.java"))
                .willReturn(planFor("src/main/java/org/x/Ghost2.java"));

        assertThatThrownBy(() -> useCase().plan(CANDIDATE_ID))
                .isInstanceOfSatisfying(PlanExhaustedException.class,
                        e -> assertThat(e.violations()).isNotEmpty());
    }

    // ── 도우미 ───────────────────────────────────────────────────────────────

    private static ContributionCandidate selectedCandidate() {
        ContributionCandidate candidate = ContributionCandidate.discover(ISSUE_ID, CLOCK);
        candidate.startAnalysis(CLOCK);
        candidate.completeAnalysis(analysis(), CLOCK);
        candidate.selectByHuman(CLOCK);
        return candidate;
    }

    /**
     * ⚠ {@code IssueAnalysisFixtures} 는 {@code candidate.domain} 패키지 전용이다.
     * 테스트 하나 때문에 공개로 넓히지 않는다 — 여기서 만든다.
     *
     * <p>추정치(2파일 · 50줄)가 {@code ScopeLimits} 로 흘러 허용치 4파일 · 100줄이 된다.
     * 아래 계획들이 1파일 · 50줄이라 <b>범위 검사에 걸리지 않는다</b> — 이 클래스가 보려는
     * 것은 재생성 루프이지 범위 판정이 아니다(그쪽은 {@code PlanValidatorTest}).
     */
    private static IssueAnalysis analysis() {
        return new IssueAnalysis("bug", IssueAnalysis.Difficulty.EASY, true, 2, 50, true, false,
                new java.math.BigDecimal("0.80"), "판정 요약");
    }

    private static AnalyzableIssue issue() {
        return new AnalyzableIssue(ISSUE_ID, REPOSITORY_ID, 42, "Poller 가 멈춘다", "본문",
                List.of(), "https://github.test/i/42", FilterOutcome.PASSED, (short) 0);
    }

    private static RepositoryContext context(boolean partial) {
        return new RepositoryContext(
                new RepositoryCoordinates("spring-projects", "spring-kafka"), "main", "sha",
                List.of(new SelectedFile(SHOWN, SelectionReason.TYPE_NAME, 60, "class Poller {}")),
                partial ? ContextBudget.of(12, 100, 50).markTruncated()
                        : ContextBudget.of(12, 120_000, 40_000),
                false, 1, Map.of());
    }

    private static ImplementationPlan goodPlan() {
        return planFor(SHOWN);
    }

    private static ImplementationPlan planFor(String path) {
        return new ImplementationPlan(
                List.of(new PlannedFile(path, PlannedFile.ChangeKind.MODIFY, "고친다")),
                "요약", "테스트 전략", 50);
    }
}
