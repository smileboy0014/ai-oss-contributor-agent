package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ossagent.candidate.adapter.out.persistence.AgentRunRepository;
import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.adapter.out.persistence.GeneratedChangeRepository;
import com.ossagent.candidate.domain.AgentRun;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.GeneratedChange;
import com.ossagent.candidate.domain.PullRequest;
import com.ossagent.support.secret.TokenRedactor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 조회 UseCase — <b>S-4 가 이 클래스의 전부</b>다.
 *
 * <p>DB 의 외부 텍스트가 HTTP 로 나가는 통로가 여기이고, 스크럽을 빠뜨리면 토큰이 나간다.
 * 회수는 폐기·재발급뿐이다.
 *
 * <p>엔티티를 Mockito 로 스텁하는 이유 — {@code ContributionCandidate} 는 {@code analysis} 를 채울
 * 공개 경로가 없고({@code discover()} 만 존재) {@code GeneratedChange} 는 팩토리도 setter 도 없다.
 * {@code ReflectionTestUtils} 로 필드를 찌르는 것은 「구현 내부 필드에 의존」 금지에 걸린다.
 */
class FindCandidatesUseCaseTest {

    /** 소스에 토큰 패턴 리터럴을 두지 않는다 — {@code secret-scan.sh} 가 커밋을 막는다. */
    private static final String FAKE_TOKEN = "ghp_" + "a".repeat(36);

    private final ContributionCandidateRepository candidates = mock(ContributionCandidateRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final GeneratedChangeRepository changes = mock(GeneratedChangeRepository.class);

    private final FindCandidatesUseCase useCase =
            new FindCandidatesUseCase(candidates, runs, changes);

    @Test
    @DisplayName("analysis 의 토큰이 스크럽된다 — 적재 측 방어가 없는 유일한 필드다")
    void analysis_의_토큰이_응답으로_새지_않는다_S4() {
        ContributionCandidate candidate = mock(ContributionCandidate.class);
        when(candidate.getId()).thenReturn(1L);
        when(candidate.getAnalysis()).thenReturn("이 이슈는 " + FAKE_TOKEN + " 로 재현됩니다");
        when(candidates.findById(1L)).thenReturn(Optional.of(candidate));
        when(runs.findByCandidateIdOrderByStartedAtAsc(1L)).thenReturn(List.of());
        when(changes.findFirstByCandidateIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());

        CandidateDetailView detail = useCase.findDetail(1L);

        assertThat(detail.analysis())
                .as("analysis 는 LLM 응답이고 적재 측 방어가 없다 — 여기가 유일한 그물이다")
                .doesNotContain(FAKE_TOKEN)
                .contains(TokenRedactor.MASK);
    }

    @Test
    @DisplayName("errorMessage 의 토큰이 스크럽된다 — 적재 측과 이중 방어")
    void errorMessage_의_토큰이_응답으로_새지_않는다_S4() {
        ContributionCandidate candidate = mock(ContributionCandidate.class);
        when(candidate.getId()).thenReturn(1L);
        when(candidates.findById(1L)).thenReturn(Optional.of(candidate));

        AgentRun run = mock(AgentRun.class);
        when(run.getErrorMessage())
                .thenReturn("GET https://api.github.com/repos?access_token=" + FAKE_TOKEN + " 실패");
        when(runs.findByCandidateIdOrderByStartedAtAsc(1L)).thenReturn(List.of(run));
        when(changes.findFirstByCandidateIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());

        CandidateDetailView detail = useCase.findDetail(1L);

        assertThat(detail.runs()).hasSize(1);
        assertThat(detail.runs().getFirst().errorMessage())
                .as("예외 메시지에 토큰 붙은 URL 이 담기는 것이 S-4 의 가장 흔한 사고다")
                .doesNotContain(FAKE_TOKEN);
    }

    @Test
    @DisplayName("diff 본문 대신 크기와 해시만 나간다")
    void diff_본문이_응답에_실리지_않는다_S4() {
        String diff = "+ secret = " + FAKE_TOKEN + "\n";
        ContributionCandidate candidate = mock(ContributionCandidate.class);
        when(candidate.getId()).thenReturn(1L);
        when(candidates.findById(1L)).thenReturn(Optional.of(candidate));
        when(runs.findByCandidateIdOrderByStartedAtAsc(1L)).thenReturn(List.of());

        GeneratedChange change = mock(GeneratedChange.class);
        when(change.getDiff()).thenReturn(diff);
        when(change.getTestResult()).thenReturn("BUILD SUCCESSFUL");
        when(change.getReviewResult()).thenReturn(null);
        when(changes.findFirstByCandidateIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(change));

        var summary = useCase.findDetail(1L).latestChange();

        assertThat(summary.diffSize()).isEqualTo(diff.length());
        assertThat(summary.diffSha256())
                .as("같은 diff 인지 식별하는 용도. 앞 12자면 충분하고 전체를 줄 이유가 없다")
                .hasSize(12);
        assertThat(summary.hasTestResult()).isTrue();
        assertThat(summary.hasReviewResult()).isFalse();
        assertThat(summary.toString())
                .as("대상 저장소가 커밋해 둔 시크릿이 diff 를 타고 나간다 — 본문을 담지 않는 것이 방어다")
                .doesNotContain(FAKE_TOKEN);
    }

    @Test
    @DisplayName("PR URL 에 자격증명이 섞여도 응답으로 나가지 않는다")
    void PR_URL_의_자격증명이_응답으로_새지_않는다_S4() {
        ContributionCandidate candidate = mock(ContributionCandidate.class);
        when(candidate.getId()).thenReturn(1L);
        when(candidates.findById(1L)).thenReturn(Optional.of(candidate));
        when(runs.findByCandidateIdOrderByStartedAtAsc(1L)).thenReturn(List.of());
        when(changes.findFirstByCandidateIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());

        PullRequest pullRequest = mock(PullRequest.class);
        // #22 가 push URL 에 자격증명을 박는 형태로 넣을 수 있다 — 가장 흔한 구현이다
        when(pullRequest.getForkUrl())
                .thenReturn("https://x-access-token:" + FAKE_TOKEN + "@github.com/me/spring-kafka.git");
        when(pullRequest.getPrUrl())
                .thenReturn("https://github.com/spring-projects/spring-kafka/pull/1?t=" + FAKE_TOKEN);
        when(candidate.getPullRequest()).thenReturn(pullRequest);

        var view = useCase.findDetail(1L).pullRequest();

        assertThat(view.forkUrl())
                .as("적재 측(#22)을 믿지 않는다 — errorMessage 에 적용한 논리와 같다")
                .doesNotContain(FAKE_TOKEN);
        assertThat(view.prUrl()).doesNotContain(FAKE_TOKEN);
    }

    @Test
    @DisplayName("같은 diff 는 같은 해시, 다른 diff 는 다른 해시")
    void 해시가_diff_를_식별한다() {
        assertThat(hashOf("같은 내용")).isEqualTo(hashOf("같은 내용"));
        assertThat(hashOf("내용 A")).isNotEqualTo(hashOf("내용 B"));
    }

    @Test
    @DisplayName("없는 후보는 CandidateNotFoundException")
    void 없는_후보는_예외다() {
        when(candidates.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.findDetail(404L))
                .isInstanceOf(CandidateNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("변경분이 없으면 null 이고 터지지 않는다")
    void 변경분이_없어도_상세를_만든다() {
        ContributionCandidate candidate = mock(ContributionCandidate.class);
        when(candidate.getId()).thenReturn(1L);
        when(candidates.findById(1L)).thenReturn(Optional.of(candidate));
        when(runs.findByCandidateIdOrderByStartedAtAsc(1L)).thenReturn(List.of());
        when(changes.findFirstByCandidateIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());

        CandidateDetailView detail = useCase.findDetail(1L);

        assertThat(detail.latestChange()).isNull();
        assertThat(detail.pullRequest())
                .as("PR 이 아직 없는 후보가 대부분이다")
                .isNull();
    }

    @Test
    @DisplayName("목록은 필터와 페이지를 그대로 리포지토리에 넘긴다")
    void 목록은_조건을_그대로_위임한다() {
        when(candidates.findSummaries(any(), any(), any(), any())).thenReturn(Page.empty());

        useCase.findAll(CandidateQuery.all(), 0, CandidateQuery.DEFAULT_SIZE);

        verify(candidates).findSummaries(isNull(), isNull(), isNull(),
                eq(PageRequest.of(0, CandidateQuery.DEFAULT_SIZE)));
    }

    private String hashOf(String diff) {
        ContributionCandidate candidate = mock(ContributionCandidate.class);
        when(candidate.getId()).thenReturn(1L);
        when(candidates.findById(1L)).thenReturn(Optional.of(candidate));
        when(runs.findByCandidateIdOrderByStartedAtAsc(1L)).thenReturn(List.of());

        GeneratedChange change = mock(GeneratedChange.class);
        when(change.getDiff()).thenReturn(diff);
        when(changes.findFirstByCandidateIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(change));

        return useCase.findDetail(1L).latestChange().diffSha256();
    }
}
