package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.TargetWorkspaceSource;
import com.ossagent.agent.domain.WorkspaceDiff;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.FakeChangeVerifier;
import com.ossagent.candidate.domain.FakeDiffReviewer;
import com.ossagent.candidate.domain.CodingAgent;
import com.ossagent.candidate.domain.CodingInput;
import com.ossagent.candidate.domain.GeneratedFile;
import com.ossagent.candidate.domain.ImplementationPlan;
import com.ossagent.candidate.domain.PlannedFile;
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
 * 🔴 <b>계획 밖 경로 게이트에 입력이 도달하는지</b>를 본다 — #18 · 이슈 완료 조건 1.
 *
 * <h2>이 테스트가 없을 때 무엇이 비어 있었나 — 실측</h2>
 *
 * <p>{@code WorkspaceDiffTest} 가 판정 <b>값 타입</b>을 덮고 있었지만,
 * {@code ImplementCandidateUseCase} 의 <b>호출 지점을 통째로 주석 처리해도
 * 전체 스위트가 초록이었다</b>(측정: {@code ./gradlew test --rerun-tasks} → exit=0).
 * 가드가 있는 것과 <b>그 가드에 입력이 도달하는 것</b>은 다른 말이다 —
 * 테스트에서는 {@code executorReady()} 가 거짓이라 파이프라인에 <b>진입조차 하지 않았다.</b>
 *
 * <h2>왜 {@code @FakeAdapter} 빈으로 만들지 않았나</h2>
 *
 * <p>대역을 스캔되는 빈으로 두면 <b>모든 통합 테스트에서</b> {@code executorReady()} 가
 * 참이 되고, 「실행기가 없으면 착수를 거부한다(503)」를 고정한
 * {@code CandidateApprovalApiTest} 가 <b>반대 사실을 검증</b>하게 된다.
 * 그래서 이 파일 안에서만 사는 대역을 손으로 조립한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ImplementCandidatePipelineTest {

    private static final String PLANNED = "src/main/java/A.java";
    private static final Long CANDIDATE_ID = 7L;

    @Test
    @DisplayName("🔴 diff 가 계획 밖 파일을 담으면 중단하고 검증으로 넘어가지 않는다")
    void 계획_밖_경로면_중단한다(@TempDir Path root) throws Exception {
        // 계획에 없는 build.gradle 이 diff 에 섞였다 — 포맷터가 건드린 모양이다
        Fixture f = fixture(root, Set.of(PLANNED, "build.gradle"));

        f.useCase().implement(CANDIDATE_ID);

        verify(f.retries()).fail(eq(CANDIDATE_ID), anyInt(), any(), anyString());
        assertThat(f.verifier().requests())
                .as("🔴 계획 밖인데 검증까지 갔다면 게이트가 없는 것과 같다")
                .isEmpty();
        verify(f.writer(), never()).recordChange(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("계획 안이면 영속화하고 검증으로 넘긴다 — 항상 막는 고장이 아니다")
    void 계획_안이면_검증까지_간다(@TempDir Path root) throws Exception {
        Fixture f = fixture(root, Set.of(PLANNED));

        f.useCase().implement(CANDIDATE_ID);

        verify(f.writer()).recordChange(eq(CANDIDATE_ID), anyString(), anyString());
        assertThat(f.verifier().requests()).hasSize(1);
        verify(f.retries(), never()).fail(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("🔴 브랜치 이름이 PRD §14 형식이다 — 대상 저장소에 나가는 이름이다")
    void 브랜치_이름은_PRD_형식이다(@TempDir Path root) throws Exception {
        Fixture f = fixture(root, Set.of(PLANNED));

        f.useCase().implement(CANDIDATE_ID);

        assertThat(f.workspaces().branches).containsExactly("oss-agent/issue-42-fix");
    }

    @Test
    @DisplayName("🔴 모델이 상위 참조 경로를 돌려주면 호스트에 쓰지 않는다 — S-3")
    void 워크스페이스_밖_경로는_쓰지_않는다_S3(@TempDir Path root) throws Exception {
        // 🔴 계획 검사와 다른 축이다 — 조기 차단(CodingOutOfPlanException)은 **모델 출력**을
        //    계획과 대조하지만, 그 계획 자체가 대상 저장소 트리에서 나온다.
        //    여기서는 대역이 그 검사를 통과한 상황을 만들어 **쓰기 직전**의 방어만 본다
        Fixture f = fixture(root, Set.of(PLANNED), "../escaped.txt");
        Path escaped = root.toRealPath().resolve("escaped.txt");

        f.useCase().implement(CANDIDATE_ID);

        assertThat(escaped)
                .as("🔴 워크스페이스 밖에 파일이 생겼다면 S-3 가 뚫린 것이다")
                .doesNotExist();
        verify(f.retries()).fail(eq(CANDIDATE_ID), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("계획이 CREATE 여도 같은 판정을 한다 — 표본이 MODIFY 뿐이면 이 축이 비어 있다")
    void 계획이_CREATE_여도_판정한다(@TempDir Path root) throws Exception {
        // ⚠ 「계획 파일이 실재하는가」 검사는 CREATE 를 통과시킬 수 없어 표본이 한쪽으로
        //    쏠리기 쉬운 자리다. 경로 집합 대조는 변경 종류를 보지 않는다는 것을 고정한다
        Fixture inside = fixture(root, Set.of(PLANNED), PLANNED, PlannedFile.ChangeKind.CREATE);
        inside.useCase().implement(CANDIDATE_ID);
        verify(inside.writer()).recordChange(eq(CANDIDATE_ID), anyString(), anyString());

        Fixture outside = fixture(root, Set.of(PLANNED, "build.gradle"), PLANNED,
                PlannedFile.ChangeKind.CREATE);
        outside.useCase().implement(CANDIDATE_ID);
        verify(outside.retries()).fail(eq(CANDIDATE_ID), anyInt(), any(), anyString());
    }

    // ── 조립 ────────────────────────────────────────────────────────────────

    private record Fixture(ImplementCandidateUseCase useCase,
            CandidateImplementationWriter writer,
            FakeTargetWorkspaceSource workspaces,
            FakeChangeVerifier verifier,
            CandidateRetryWriter retries) {
    }

    private static Fixture fixture(Path root, Set<String> changedPaths) throws Exception {
        return fixture(root, changedPaths, PLANNED);
    }

    private static Fixture fixture(Path root, Set<String> changedPaths, String generatedPath)
            throws Exception {
        return fixture(root, changedPaths, generatedPath, PlannedFile.ChangeKind.MODIFY);
    }

    private static Fixture fixture(Path root, Set<String> changedPaths, String generatedPath,
            PlannedFile.ChangeKind change) throws Exception {
        Path real = root.toRealPath();
        // ⚠ 픽스처를 한 테스트에서 두 번 만들 수 있어야 한다 — 워크스페이스 이름을 나눈다
        Path dir = real.resolve("ws-" + Integer.toHexString(System.identityHashCode(changedPaths)));
        Files.createDirectories(dir);
        SandboxWorkspace workspace = SandboxWorkspace.under(dir, real);

        ImplementationPlan plan = new ImplementationPlan(
                List.of(new PlannedFile(PLANNED, change, "고친다")),
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

        FakeTargetWorkspaceSource workspaces =
                new FakeTargetWorkspaceSource(workspace, changedPaths);
        FakeChangeVerifier verifier = new FakeChangeVerifier().givenPassing();
        FakeDiffReviewer reviewer = new FakeDiffReviewer();
        // 🔴 루프의 전이 구간은 별도 빈이다(#21). 여기서는 전이를 세지 않고
        //    「경로 게이트가 검증까지 넘기는가」만 보므로 mock 으로 충분하다 —
        //    바퀴 수·전이는 ImplementCandidateRetryLoopTest 가 본다
        CandidateRetryWriter retries = mock(CandidateRetryWriter.class);

        ImplementCandidateUseCase useCase = new ImplementCandidateUseCase(writer, planner,
                contexts, policies, provider(workspaces), provider(new FakeCodingAgent(generatedPath)),
                provider(verifier), provider(reviewer), retries,
                new ExecutionProperties(3, 1800),
                new com.ossagent.support.observability.PipelineMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));

        return new Fixture(useCase, writer, workspaces, verifier, retries);
    }

    private static RepositoryCoordinates coordinates() {
        return new RepositoryCoordinates("spring-projects", "spring-kafka");
    }

    private static RepositoryContext context() {
        return new RepositoryContext(coordinates(), "main", "sha", List.of(),
                ContextBudget.of(12, 100_000, 20_000), false, 0, Map.of());
    }

    /** {@code getIfAvailable} 하나만 쓰인다 — 나머지는 이 테스트가 부르지 않는다 */
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

    private static final class FakeTargetWorkspaceSource implements TargetWorkspaceSource {

        private final SandboxWorkspace workspace;
        private final Set<String> changedPaths;
        private final List<String> branches = new ArrayList<>();

        private FakeTargetWorkspaceSource(SandboxWorkspace workspace, Set<String> changedPaths) {
            this.workspace = workspace;
            this.changedPaths = changedPaths;
        }

        @Override
        public SandboxWorkspace fetch(RepositoryCoordinates coordinates, String branchName) {
            branches.add(branchName);
            return workspace;
        }

        @Override
        public WorkspaceDiff diff(SandboxWorkspace workspace) {
            return new WorkspaceDiff("--- a/A\n+++ b/A\n", changedPaths);
        }

        @Override
        public Set<String> apply(SandboxWorkspace workspace, String unifiedDiff) {
            throw new UnsupportedOperationException("착수 경로는 diff 를 입히지 않는다 — PR 게이트의 일이다");
        }
    }

    private static final class FakeCodingAgent implements CodingAgent {

        private final String path;

        private FakeCodingAgent(String path) {
            this.path = path;
        }

        @Override
        public List<GeneratedFile> write(Long candidateId, int attempt, CodingInput input) {
            return List.of(new GeneratedFile(path, "class A {}"));
        }
    }

}
