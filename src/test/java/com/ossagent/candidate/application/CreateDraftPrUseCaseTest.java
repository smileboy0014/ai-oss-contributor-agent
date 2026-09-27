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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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

    private CandidatePrWriter writer;
    private FindAnalyzableIssuesUseCase issues;
    private AnalyzeRepositoryPolicyUseCase policies;
    private FakeForkPublisher forks;
    private FakeDraftPrPublisher draftPrs;
    private CreateDraftPrUseCase useCase;

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

        useCase = new CreateDraftPrUseCase(writer, issues, policies, forks, draftPrs,
                new PipelineMetrics(new SimpleMeterRegistry()));
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
    @DisplayName("올라간 브랜치 기록이 없으면 PR 을 만들지 않는다")
    void 브랜치_기록이_없으면_PR_을_만들지_않는다() {
        given(writer.load(CANDIDATE_ID))
                .willReturn(snapshot(CandidateStatus.READY_FOR_PR, null, null));

        assertThatThrownBy(() -> useCase.create(CANDIDATE_ID))
                .isInstanceOf(DraftPrException.class);
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
                ISSUE_ID, CandidateStatus.READY_FOR_PR, "oss-agent/issue-12-x", "abc123",
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

    private static CandidatePrWriter.PrSnapshot readyForPr() {
        return snapshot(CandidateStatus.READY_FOR_PR, "oss-agent/issue-12-x", "abc123");
    }

    private static CandidatePrWriter.PrSnapshot snapshot(CandidateStatus status, String branch,
            String sha) {
        return new CandidatePrWriter.PrSnapshot(ISSUE_ID, status, branch, sha, null, null, null);
    }

    private static AnalyzableIssue issue() {
        return new AnalyzableIssue(ISSUE_ID, REPOSITORY_ID, 12, "Fix NPE in listener",
                "본문", List.of(), "https://github.com/spring-projects/spring-kafka/issues/12",
                FilterOutcome.PASSED, (short) 1);
    }
}
