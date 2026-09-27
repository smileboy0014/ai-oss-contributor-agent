package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.TargetWorkspaceSource;
import com.ossagent.agent.domain.WorkspaceDiff;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.CandidateTransitionException;
import com.ossagent.candidate.domain.StatusTransition;
import com.ossagent.issue.application.FindAnalyzableIssuesUseCase;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.pullrequest.domain.DraftPrException;
import com.ossagent.pullrequest.domain.FakeDraftPrPublisher;
import com.ossagent.pullrequest.domain.FakeForkPublisher;
import com.ossagent.pullrequest.domain.OpenedPullRequest;
import com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase;
import com.ossagent.repository.application.PullRequestTarget;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.observability.PipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>세 번째 승인 게이트</b>의 업무 규칙 — S-6 · S-2 · S-5 (#23).
 *
 * <p>HTTP 표면({@code CandidateApprovalApiTest})은 상태 코드만 본다. 여기서 보는 것은
 * <b>순서와 거부</b>다 — 「대외 호출이 실패하면 전이가 커밋되지 않는가」처럼
 * 상태 코드로는 드러나지 않는 것들이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CreateDraftPrUseCaseTest {

    private static final Long CANDIDATE_ID = 77L;
    private static final Long ISSUE_ID = 9001L;
    private static final Long REPOSITORY_ID = 3L;
    private static final RepositoryCoordinates UPSTREAM =
            new RepositoryCoordinates("spring-projects", "spring-kafka");

    private static final String CHANGED_PATH = "src/main/java/A.java";
    private static final String DIFF = "--- a/" + CHANGED_PATH + "\n+++ b/" + CHANGED_PATH + "\n@@ -1 +1 @@\n-x\n+y\n";

    private CandidatePrWriter writer;
    private FindAnalyzableIssuesUseCase issues;
    private AnalyzeRepositoryPolicyUseCase policies;
    private FakeForkPublisher forks;
    private FakeDraftPrPublisher draftPrs;
    private FakeWorkspaceSource workspaces;
    private CreateDraftPrUseCase useCase;
    private Path tempRoot;

    @BeforeEach
    void setUp() {
        writer = mock(CandidatePrWriter.class);
        given(writer.load(CANDIDATE_ID)).willReturn(readyForPr());
        given(writer.attachPullRequest(anyLong(), anyString(), anyString(), anyInt(), anyString()))
                .willReturn(new StatusTransition(
                        CandidateStatus.READY_FOR_PR, CandidateStatus.PR_CREATED));

        issues = mock(FindAnalyzableIssuesUseCase.class);
        given(issues.findOne(ISSUE_ID)).willReturn(Optional.of(issue()));

        policies = mock(AnalyzeRepositoryPolicyUseCase.class);
        given(policies.constraintsOf(REPOSITORY_ID)).willReturn(ContributionConstraints.unknown());
        given(policies.findPullRequestTarget(REPOSITORY_ID))
                .willReturn(new PullRequestTarget(UPSTREAM, "main", null));

        forks = new FakeForkPublisher();
        forks.givenForkOwner("fork-owner");
        draftPrs = new FakeDraftPrPublisher();
        workspaces = new FakeWorkspaceSource(newWorkspace());

        useCase = new CreateDraftPrUseCase(writer, issues, policies, forks, draftPrs,
                provider(workspaces), new PipelineMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempRoot != null) {
            try (var walk = Files.walk(tempRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                        // 임시 디렉토리 정리 실패는 테스트 판정과 무관하다
                    }
                });
            }
        }
    }

    // ────────────────────────── Fork push (S-1) ──────────────────────────

    @Test
    @DisplayName("PR 을 열기 전에 Fork 에 push 하고, PR 의 head 는 그 커밋이다 — S-1")
    void push_가_PR_생성보다_먼저다_S1() {
        forks.givenCommitSha("pushed-sha-1");

        useCase.create(CANDIDATE_ID);

        assertThat(forks.publishRequests())
                .as("🔴 이 단언이 없던 동안 publish() 의 운영 호출자가 0 개였다 — PR 게이트가 항상 실패했다")
                .hasSize(1);
        assertThat(draftPrs.openDraftRequests()).hasSize(1);
        assertThat(draftPrs.openDraftRequests().get(0).head().commitSha()).isEqualTo("pushed-sha-1");
        verify(writer).recordPublished(CANDIDATE_ID, "pushed-sha-1");
    }

    @Test
    @DisplayName("push 되는 파일은 저장된 diff 를 새 워크스페이스에 입혀 만든다 — 착수 디렉토리를 다시 읽지 않는다")
    void 파일은_diff_를_입혀_만든다() {
        useCase.create(CANDIDATE_ID);

        assertThat(workspaces.appliedDiffs()).containsExactly(DIFF);
        assertThat(forks.publishRequests().get(0).changes())
                .extracting("path")
                .containsExactly(CHANGED_PATH);
    }

    @Test
    @DisplayName("Fork 동기화가 어긋나면 push 도 PR 도 하지 않는다 — S-2")
    void 동기화가_어긋나면_push_도_PR_도_없다_S2() {
        forks.givenSyncOutcome(com.ossagent.pullrequest.domain.SyncOutcome.CONFLICT);

        assertThatThrownBy(() -> useCase.create(CANDIDATE_ID))
                .isInstanceOf(DraftPrException.class);

        assertThat(forks.publishRequests()).isEmpty();
        assertThat(draftPrs.openDraftRequests()).isEmpty();
        verify(writer, never()).attachPullRequest(anyLong(), anyString(), anyString(), anyInt(),
                anyString());
    }

    @Test
    @DisplayName("전에 push 한 기록(commitSha)이 있으면 새 브랜치 생성이 아니라 갱신이다")
    void 앞_호출이_push_까지_했으면_갱신으로_다시_올린다() {
        given(writer.load(CANDIDATE_ID))
                .willReturn(snapshot(CandidateStatus.READY_FOR_PR, "oss-agent/issue-12-x", "earlier-sha"));

        useCase.create(CANDIDATE_ID);

        assertThat(forks.publishRequests().get(0).allowUpdate()).isTrue();
    }

    @Test
    @DisplayName("첫 push 는 브랜치 생성이다 — 우리 기록에 없는 브랜치를 덮어쓰지 않는다")
    void 첫_push_는_생성이다() {
        useCase.create(CANDIDATE_ID);

        assertThat(forks.publishRequests().get(0).allowUpdate()).isFalse();
    }

    @Test
    @DisplayName("push 가 실패하면 PR 을 열지 않고 전이하지 않는다 — S-1 · S-6")
    void push_실패면_PR_도_전이도_없다_S1() {
        forks.givenPublishFails(new com.ossagent.pullrequest.domain.ForkPublishException("브랜치가 이미 있습니다"));

        assertThatThrownBy(() -> useCase.create(CANDIDATE_ID))
                .isInstanceOf(com.ossagent.pullrequest.domain.ForkPublishException.class);

        assertThat(draftPrs.openDraftRequests()).isEmpty();
        verify(writer, never()).recordPublished(anyLong(), anyString());
        verify(writer, never()).attachPullRequest(anyLong(), anyString(), anyString(), anyInt(),
                anyString());
    }

    // ────────────────────────── 성공 경로 ──────────────────────────

    @Test
    @DisplayName("READY_FOR_PR 후보는 Draft PR 을 만들고 종단으로 전이한다")
    void 성공하면_PR_을_만들고_전이한다_S6() {
        CreateDraftPrUseCase.Result result = useCase.create(CANDIDATE_ID);

        assertThat(draftPrs.openDraftRequests())
                .as("PR 을 실제로 만들어야 한다 — 이 단언이 없으면 아래가 전부 공허하다")
                .hasSize(1);
        assertThat(result.transition().to()).isEqualTo(CandidateStatus.PR_CREATED);
        assertThat(result.transition().to().isTerminal()).isTrue();
        assertThat(result.reusedExisting()).isFalse();
        verify(writer).attachPullRequest(anyLong(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("fork 주소는 조립이 아니라 응답에서 온다 — S-1 의 증거")
    void fork_주소는_관측값이다_S1() {
        draftPrs.givenAlreadyOpen(new OpenedPullRequest(31,
                "https://github.com/spring-projects/spring-kafka/pull/31",
                "fork-owner:oss-agent/issue-12-x",
                // 🔴 GitHub 이 같은 이름을 피해 만든 fork — 조립했다면 이 값이 나올 수 없다
                "https://github.com/fork-owner/spring-kafka-1"));

        useCase.create(CANDIDATE_ID);

        verify(writer).attachPullRequest(anyLong(),
                org.mockito.ArgumentMatchers.eq("https://github.com/fork-owner/spring-kafka-1"),
                anyString(), anyInt(), anyString());
    }

    // ────────────────────────── 거부 ──────────────────────────

    @Test
    @DisplayName("READY_FOR_PR 이 아니면 대외 호출을 하나도 태우지 않는다")
    void 상태가_틀리면_대외_호출을_하지_않는다_S6() {
        given(writer.load(CANDIDATE_ID)).willReturn(
                snapshot(CandidateStatus.SELECTED, "oss-agent/issue-12-x", "abc123"));

        assertThatThrownBy(() -> useCase.create(CANDIDATE_ID))
                .isInstanceOf(CandidateTransitionException.class);

        assertThat(draftPrs.openDraftRequests())
                .as("잘못 눌렀는데 GitHub 호출을 태우고 나서 409 가 되면 안 된다")
                .isEmpty();
        verify(writer, never()).attachPullRequest(anyLong(), anyString(), anyString(), anyInt(),
                anyString());
    }

    @Test
    @DisplayName("AI 기여가 허용이 아니면 PR 을 만들지 않는다 — S-5")
    void 기여_금지_보류면_PR_을_만들지_않는다_S5() {
        // 🔴 #68 의 강등(TRUE → NULL)이 착수와 PR 생성 사이에 일어날 수 있다.
        //    여기서 다시 보지 않으면 금지된 저장소에 Draft PR 이 나간다 — 되돌릴 수 없다
        org.mockito.BDDMockito.willThrow(new ContributionNotAllowedException(
                        REPOSITORY_ID, ContributionNotAllowedException.Reason.UNDETERMINED))
                .given(policies).assertContributionAllowed(REPOSITORY_ID);

        assertThatThrownBy(() -> useCase.create(CANDIDATE_ID))
                .isInstanceOf(ContributionNotAllowedException.class);

        assertThat(draftPrs.openDraftRequests()).isEmpty();
        verify(writer, never()).attachPullRequest(anyLong(), anyString(), anyString(), anyInt(),
                anyString());
    }

    @Test
    @DisplayName("정책 재확인은 PR 을 만들기 전에 일어난다 — 순서가 방어다")
    void 정책_재확인이_먼저다_S5() {
        useCase.create(CANDIDATE_ID);

        var order = org.mockito.Mockito.inOrder(policies, writer);
        order.verify(policies).assertContributionAllowed(REPOSITORY_ID);
        order.verify(writer).attachPullRequest(anyLong(), anyString(), anyString(), anyInt(),
                anyString());
    }

    // ────────────────────────── 중복 방지 ──────────────────────────

    @Test
    @DisplayName("이미 열린 PR 이 있으면 두 번째를 만들지 않는다 — S-2")
    void 이미_열린_PR_이_있으면_새로_만들지_않는다_S2() {
        draftPrs.givenAlreadyOpen(new OpenedPullRequest(42,
                "https://github.com/spring-projects/spring-kafka/pull/42",
                "fork-owner:oss-agent/issue-12-x",
                "https://github.com/fork-owner/spring-kafka"));

        CreateDraftPrUseCase.Result result = useCase.create(CANDIDATE_ID);

        assertThat(draftPrs.openDraftRequests())
                .as("남의 저장소에 중복 PR 을 여는 것은 스팸으로 취급된다")
                .isEmpty();
        assertThat(result.pullRequest().number()).isEqualTo(42);
        assertThat(result.reusedExisting())
                .as("사람이 「내가 만든 게 아니라 붙인 것」임을 알아야 한다")
                .isTrue();
    }

    // ────────────────────────── 순서 (FR-9) ──────────────────────────

    @Test
    @DisplayName("PR 생성이 실패하면 전이가 커밋되지 않는다")
    void 대외_실패면_전이하지_않는다_S6() {
        draftPrs.givenOpenDraftFails(new DraftPrException("GitHub 이 422 를 돌려줬다"));

        assertThatThrownBy(() -> useCase.create(CANDIDATE_ID))
                .isInstanceOf(DraftPrException.class);

        verify(writer, never()).attachPullRequest(anyLong(), anyString(), anyString(), anyInt(),
                anyString());
    }

    @Test
    @DisplayName("검증을 통과한 변경분(브랜치·diff) 기록이 없으면 push 도 PR 도 하지 않는다")
    void 브랜치_기록이_없으면_PR_을_만들지_않는다() {
        given(writer.load(CANDIDATE_ID))
                .willReturn(snapshot(CandidateStatus.READY_FOR_PR, null, null));

        assertThatThrownBy(() -> useCase.create(CANDIDATE_ID))
                .isInstanceOf(DraftPrException.class);
        assertThat(forks.publishRequests()).isEmpty();
        assertThat(draftPrs.openDraftRequests()).isEmpty();
    }

    @Test
    @DisplayName("우리가 만들지 않은 브랜치로는 PR 을 열지 않는다")
    void 우리_형식이_아닌_브랜치는_거부한다() {
        // PRD §14 형식(oss-agent/issue-{n}-{slug})이 아니면 우리가 만든 브랜치가 아니다
        given(writer.load(CANDIDATE_ID))
                .willReturn(snapshot(CandidateStatus.READY_FOR_PR, "main", "abc123"));

        assertThatThrownBy(() -> useCase.create(CANDIDATE_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(draftPrs.openDraftRequests()).isEmpty();
    }

    // ────────────────────────── 본문 (S-5) ──────────────────────────

    @Test
    @DisplayName("대상 저장소 템플릿이 본문 맨 위에 실린다 — S-5")
    void 템플릿이_본문에_실린다_S5() {
        given(policies.findPullRequestTarget(REPOSITORY_ID)).willReturn(
                new PullRequestTarget(UPSTREAM, "main", "## Checklist\n- [ ] tests added"));

        useCase.create(CANDIDATE_ID);

        String body = draftPrs.openDraftRequests().get(0).body().value();
        assertThat(body).startsWith("## Checklist");
        assertThat(body)
                .as("체크박스를 우리가 채우면 거짓 진술이 남의 저장소에 나간다")
                .contains("- [ ] tests added");
    }

    @Test
    @DisplayName("본문에 이슈 참조와 AI 생성 고지가 들어간다")
    void 본문에_이슈_참조와_AI_고지가_있다() {
        useCase.create(CANDIDATE_ID);

        String body = draftPrs.openDraftRequests().get(0).body().value();
        assertThat(body).contains("See #12");
        assertThat(body).contains("Automated contribution");
        assertThat(body)
                .as("언제 닫을지는 메인테이너가 정한다 — 자동 닫기 키워드를 붙이지 않는다")
                .doesNotContain("Fixes #").doesNotContain("Closes #");
    }

    @Test
    @DisplayName("검증 결과는 판정이 아니라 실행 기록으로 적는다 — Q-4 가 열려 있다")
    void 검증_결과를_통과로_단정하지_않는다() {
        given(writer.load(CANDIDATE_ID)).willReturn(new CandidatePrWriter.PrSnapshot(
                ISSUE_ID, CandidateStatus.READY_FOR_PR, "oss-agent/issue-12-x", null, DIFF,
                "BUILD SUCCESSFUL in 12s", null, null));

        useCase.create(CANDIDATE_ID);

        String body = draftPrs.openDraftRequests().get(0).body().value();
        assertThat(body).contains("BUILD SUCCESSFUL in 12s");
        assertThat(body)
                .as("실행 단계가 network=none 이라 정상 코드인데 실패할 수 있다(Q-4). "
                        + "그 미결을 「테스트 통과」로 남의 저장소에 내보내지 않는다")
                .contains("offline sandbox")
                .contains("not as a pass/fail verdict");
    }

    // ────────────────────────── 픽스처 ──────────────────────────

    /** 검증은 끝났고 아직 push 하지 않은 후보 — commitSha 가 없는 것이 정상이다. */
    private static CandidatePrWriter.PrSnapshot readyForPr() {
        return snapshot(CandidateStatus.READY_FOR_PR, "oss-agent/issue-12-x", null);
    }

    private static CandidatePrWriter.PrSnapshot snapshot(CandidateStatus status, String branch,
            String sha) {
        return new CandidatePrWriter.PrSnapshot(ISSUE_ID, status, branch, sha,
                branch == null ? null : DIFF, null, null, null);
    }

    private SandboxWorkspace newWorkspace() {
        try {
            tempRoot = Files.createTempDirectory("pr-usecase-").toRealPath();
            Path dir = Files.createDirectories(tempRoot.resolve("ws"));
            return SandboxWorkspace.under(dir, tempRoot);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    /**
     * 워크스페이스 대역 — {@code apply} 가 diff 대신 바뀐 파일을 <b>실제로</b> 써 둔다.
     * UseCase 가 그 파일을 읽어 {@code FileChange} 를 만드는 경로가 실제 파일시스템을 타야
     * 「존재하면 modified · 없으면 deleted」 분기가 공허하지 않다.
     */
    private static final class FakeWorkspaceSource implements TargetWorkspaceSource {

        private final SandboxWorkspace workspace;
        private final List<String> appliedDiffs = new ArrayList<>();

        private FakeWorkspaceSource(SandboxWorkspace workspace) {
            this.workspace = workspace;
        }

        List<String> appliedDiffs() {
            return List.copyOf(appliedDiffs);
        }

        @Override
        public SandboxWorkspace fetch(RepositoryCoordinates coordinates, String branchName) {
            return workspace;
        }

        @Override
        public WorkspaceDiff diff(SandboxWorkspace workspace) {
            return new WorkspaceDiff(DIFF, Set.of(CHANGED_PATH));
        }

        @Override
        public void apply(SandboxWorkspace workspace, String unifiedDiff) {
            appliedDiffs.add(unifiedDiff);
            try {
                Path target = workspace.resolveInside(CHANGED_PATH);
                Files.createDirectories(target.getParent());
                Files.writeString(target, "class A {}\n");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static AnalyzableIssue issue() {
        return new AnalyzableIssue(ISSUE_ID, REPOSITORY_ID, 12, "Fix NPE in listener",
                "본문", List.of(), "https://github.com/spring-projects/spring-kafka/issues/12",
                FilterOutcome.PASSED, (short) 1);
    }
}
