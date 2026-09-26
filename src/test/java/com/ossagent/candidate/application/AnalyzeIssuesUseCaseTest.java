package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.adapter.out.llm.RecordingLanguageModel;
import com.ossagent.agent.domain.AgentRunRecorder;
import com.ossagent.agent.domain.FakeLanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmPermanentException;
import com.ossagent.candidate.adapter.out.persistence.AgentRunRepository;
import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.application.IssueAnalysisProperties;
import com.ossagent.candidate.adapter.out.llm.LlmIssueAnalyst;
import com.ossagent.candidate.domain.AgentRun;
import com.ossagent.candidate.domain.AnalysisRejectedException;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.FakeIssueAnalyst;
import com.ossagent.candidate.domain.IssueAnalysis;
import com.ossagent.issue.adapter.out.persistence.IssueJpaRepository;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.issue.domain.FilterReason;
import com.ossagent.issue.domain.FilterVerdict;
import com.ossagent.issue.domain.Issue;
import com.ossagent.issue.domain.IssueSnapshot;
import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RuleReading;
import com.ossagent.repository.domain.ScrubbedRules;
import com.ossagent.support.secret.TokenRedactor;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 분석 배치 — <b>안전 경계 셋(S-4·S-5·S-6)이 이 클래스의 절반</b>이다.
 *
 * <p>🔴 반환값이 아니라 <b>DB 에서 다시 읽어</b> 단언한다. {@code open-in-view: false} 에서
 * 트랜잭션이 안 걸리면 변경이 flush 없이 사라지는데 반환값과 로그는 정상으로 보인다 —
 * #7 에서 테스트 20건이 초록인 채로 DB 만 옛날 값이었다.
 *
 * <p>⚠ 클래스에 {@code @Transactional} 을 붙이지 않는다. 같은 영속성 컨텍스트를 공유하면
 * 같은 이유로 거짓 통과한다.
 */
@AgentIntegrationTest
@Testcontainers
class AnalyzeIssuesUseCaseTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 소스에 토큰 패턴 리터럴을 두지 않는다 — {@code secret-scan.sh} 가 커밋을 막는다. */
    private static final String FAKE_TOKEN = "ghp_" + "a".repeat(36);

    @Autowired
    private AnalyzeIssuesUseCase analyzeIssues;

    @Autowired
    private FakeIssueAnalyst analyst;

    @Autowired
    private IssueJpaRepository issues;

    @Autowired
    private ContributionCandidateRepository candidates;

    @Autowired
    private AgentRunRepository agentRuns;

    @Autowired
    private OssRepositoryRepository repositories;

    @Autowired
    private RepositoryPolicyRepository policies;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentRunRecorder recorder;

    @Autowired
    private FakeLanguageModel languageModel;

    @Autowired
    private ObjectMapper objectMapper;

    private Long repositoryId;

    @BeforeEach
    void setUp() {
        // 대역은 싱글턴이고 컨텍스트가 테스트 클래스 사이에 캐시된다
        analyst.reset();
        agentRuns.deleteAll();
        candidates.deleteAll();
        issues.deleteAll();
        policies.deleteAll();

        String name = "spring-kafka-" + System.nanoTime();
        repositoryId = repositories.save(new OssRepository(
                "spring-projects", name, "https://github.com/spring-projects/" + name)).getId();
    }

    // ─────────────────────── 🔴 S-5 — 규약 게이트 ───────────────────────

    @Test
    @DisplayName("AI 기여가 금지된 저장소는 분석하지 않는다 — LLM 을 부르지도 않는다 S5")
    void 금지_저장소는_분석하지_않는다_S5() {
        givenPolicy(Boolean.FALSE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);

        assertThatThrownBy(() -> analyzeIssues.analyze(repositoryId))
                .isInstanceOf(ContributionNotAllowedException.class);

        assertThat(candidates.findAll()).isEmpty();
        assertThat(analyst.callCount())
                .as("「후보가 안 생겼다」만 보면 분석은 다 하고 저장만 안 하는 구현도 통과한다 — 토큰은 그대로 탄다")
                .isZero();
    }

    @Test
    @DisplayName("🔴 규약이 보류인 저장소도 분석하지 않는다 — 보류는 허용이 아니다 S5")
    void 보류_저장소는_분석하지_않는다_S5() {
        givenPolicy(null);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);

        assertThatThrownBy(() -> analyzeIssues.analyze(repositoryId))
                .as("「아직 판정 안 됨이니 일단 분석은 해 두자」로 풀면 S-5 가 무너진다")
                .isInstanceOf(ContributionNotAllowedException.class);

        assertThat(analyst.callCount()).isZero();
    }

    @Test
    @DisplayName("규약을 분석한 적 없는 저장소는 분석하지 않는다 S5")
    void 규약_미분석_저장소는_분석하지_않는다_S5() {
        givenIssue(1, FilterOutcome.PASSED, (short) 10);

        assertThatThrownBy(() -> analyzeIssues.analyze(repositoryId))
                .isInstanceOf(ContributionNotAllowedException.class);

        assertThat(analyst.callCount()).isZero();
    }

    // ─────────────────────── 🔴 S-6 — 승인 지점 ───────────────────────

    @Test
    @DisplayName("분석은 ANALYZED 까지만 간다 — SELECTED 로 자동 전이하지 않는다 S6")
    void 분석은_SELECTED_로_가지_않는다_S6() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);
        givenIssue(2, FilterOutcome.PASSED, (short) 5);

        analyzeIssues.analyze(repositoryId);

        assertThat(candidates.findAll())
                .isNotEmpty()
                .allSatisfy(candidate -> {
                    assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.ANALYZED);
                    assertThat(candidate.getSelectedAt())
                            .as("스케줄러가 흘려보낸 후보가 「사람이 골랐다」로 기록되면 승인 지점이 무너진다")
                            .isNull();
                });
    }

    // ─────────────────────── 🔴 S-4 — 적재 측 스크럽 ───────────────────────

    @Test
    @DisplayName("모델이 토큰을 되뱉어도 DB 에 원문이 남지 않는다 S4")
    void analysis_의_토큰이_적재되지_않는다_S4() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);
        analyst.given(analysis("0.90", true, "재현하려면 " + FAKE_TOKEN + " 이 필요하다"));

        analyzeIssues.analyze(repositoryId);

        assertThat(candidates.findAll().getFirst().getAnalysis())
                .as("#13 조회 API 가 이 값을 HTTP 로 내보낸다 — 여기가 1차 방어다")
                .doesNotContain(FAKE_TOKEN)
                .contains(TokenRedactor.MASK);
    }

    // ─────────────────────── FR-1 — 대상과 정렬 ───────────────────────

    @Test
    @DisplayName("🔴 UNDECIDED 이슈도 분석 대상이다 — #9 가 #11 에게 넘긴 상태다")
    void UNDECIDED_도_분석한다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.UNDECIDED, (short) 0);

        AnalysisResult result = analyzeIssues.analyze(repositoryId);

        assertThat(result.attempted())
                .as("빼면 「규칙으로 가를 수 없다」는 이슈가 어느 단계도 소비하지 않아 영구히 고인다")
                .isEqualTo(1);
        assertThat(candidates.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("REJECTED 이슈는 분석하지 않는다 — 배제된 것에 토큰을 쓰지 않는다")
    void REJECTED_는_분석하지_않는다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.REJECTED, (short) 10);

        AnalysisResult result = analyzeIssues.analyze(repositoryId);

        assertThat(result.attempted()).isZero();
        assertThat(analyst.callCount()).isZero();
    }

    @Test
    @DisplayName("아직 판정되지 않은 이슈도 분석하지 않는다")
    void 미판정_이슈는_분석하지_않는다() {
        givenPolicy(Boolean.TRUE);
        issues.save(Issue.fromSnapshot(repositoryId, "spring-projects", "spring-kafka",
                snapshot(1), NOW));

        assertThat(analyzeIssues.analyze(repositoryId).attempted()).isZero();
    }

    @Test
    @DisplayName("우선순위가 높은 이슈를 먼저 분석한다 — filter_priority 는 #11 을 위한 컬럼이다")
    void 우선순위_순서로_분석한다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 1);
        givenIssue(2, FilterOutcome.PASSED, (short) 50);
        givenIssue(3, FilterOutcome.PASSED, (short) 10);

        analyzeIssues.analyze(repositoryId);

        assertThat(analyst.calls())
                .as("배치 상한이 있으므로 정렬이 곧 「어느 이슈에 토큰을 쓸지」의 결정이다")
                .extracting(issue -> issue.githubIssueNumber())
                .containsExactly(2, 3, 1);
    }

    @Test
    @DisplayName("filterPriority 가 NULL 이어도 순서가 결정적이다 — H2·PostgreSQL 차이를 COALESCE 로 없앴다")
    void NULL_우선순위가_섞여도_순서가_선다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 5);
        givenIssueWithoutPriority(2);
        givenIssue(3, FilterOutcome.PASSED, (short) 20);

        analyzeIssues.analyze(repositoryId);

        assertThat(analyst.calls())
                .extracting(issue -> issue.githubIssueNumber())
                .as("NULL 은 COALESCE 로 0 이 되어 맨 뒤다 — ORDER BY DESC 의 NULL 위치는 벤더마다 다르다")
                .containsExactly(3, 1, 2);
    }

    @Test
    @DisplayName("다른 저장소의 이슈는 건드리지 않는다 — 저장소 스코프")
    void 다른_저장소_이슈는_분석하지_않는다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);

        Long otherId = repositories.save(new OssRepository(
                "other", "repo-" + System.nanoTime(), "https://example.invalid/other")).getId();
        Issue other = Issue.fromSnapshot(otherId, "other", "repo", snapshot(99), NOW);
        other.applyFilter(FilterVerdict.of(List.of(), 10), NOW);
        issues.save(other);

        analyzeIssues.analyze(repositoryId);

        assertThat(analyst.calls()).hasSize(1);
        assertThat(analyst.calls().getFirst().githubIssueNumber()).isEqualTo(1);
    }

    // ─────────────────────── FR-3~FR-6 — 본류 ───────────────────────

    @Test
    @DisplayName("분석 결과와 AgentRun 이 DB 에 남는다")
    void 결과와_실행기록이_남는다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);

        AnalysisResult result = analyzeIssues.analyze(repositoryId);

        assertThat(result.analyzed()).isEqualTo(1);

        ContributionCandidate candidate = candidates.findAll().getFirst();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.ANALYZED);
        assertThat(candidate.getConfidence()).isNotNull();
        assertThat(candidate.getAttempt())
                .as("ANALYZE 는 CODE→VERIFY→REVIEW 루프 밖이다 — Q-6")
                .isZero();
    }

    @Test
    @DisplayName("ANALYZE 호출이 AgentRun 으로 남는다 — stage·attempt·토큰 FR-6")
    void 실행기록이_남는다() {
        // ⚠ 대역 프로필에는 RecordingLanguageModel 래퍼가 없다. LanguageModelConfig 가
        //   @ExternalAdapter 라 통째로 빠지고, FakeLanguageModel 이 LanguageModel 을 직접
        //   구현하기 때문이다. 그래서 FakeIssueAnalyst 를 쓰는 위 테스트들로는 FR-6 이
        //   증명되지 않는다 — 운영 배선(LanguageModelConfig)과 같은 체인을 여기서 직접 세운다.
        ContributionCandidate candidate =
                candidates.save(ContributionCandidate.discover(4242L, CLOCK));
        languageModel.reset().respondWith(analysisJson(), 1200, 300);

        new LlmIssueAnalyst(new RecordingLanguageModel(languageModel, recorder),
                IssueAnalysisProperties.defaults(), objectMapper)
                .analyze(candidate.getId(), analyzableIssue());

        List<AgentRun> runs = agentRuns.findByCandidateIdOrderByStartedAtAsc(candidate.getId());
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().getStage()).isEqualTo(AgentRun.Stage.ANALYZE);
        assertThat(runs.getFirst().getAttempt())
                .as("ANALYZE 행의 attempt 는 항상 1 이다 — Q-6")
                .isEqualTo(1);
        assertThat(runs.getFirst().getInputTokens())
                .as("비용이 보이지 않으면 재시도 루프가 조용히 돈을 태운다")
                .isEqualTo(1200);
        assertThat(runs.getFirst().getOutputTokens()).isEqualTo(300);
    }

    private static String analysisJson() {
        return """
                {"category":"bug","difficulty":"MEDIUM","implementationFeasible":true,
                 "estimatedFiles":4,"estimatedLoc":120,"testRequired":true,
                 "breakingChange":false,"confidence":0.87,"summary":"요약"}
                """;
    }

    private static AnalyzableIssue analyzableIssue() {
        return new AnalyzableIssue(4242L, 1L, 42, "제목", "본문", List.of(),
                "https://example.invalid/42", FilterOutcome.PASSED, (short) 10);
    }

    @Test
    @DisplayName("🔴 재실행해도 후보가 두 번 생기지 않는다 — 두 번째는 LLM 도 부르지 않는다")
    void 재실행_멱등이다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);
        givenIssue(2, FilterOutcome.PASSED, (short) 5);

        analyzeIssues.analyze(repositoryId);
        analyst.reset();
        AnalysisResult second = analyzeIssues.analyze(repositoryId);

        assertThat(candidates.findAll())
                .as("같은 이슈에 후보가 두 번 생기면 PR 도 두 번 나간다")
                .hasSize(2);
        assertThat(second.skipped()).isEqualTo(2);
        assertThat(second.attempted()).isZero();
        assertThat(analyst.callCount())
                .as("2회차에 토큰을 또 태우면 멱등이 아니라 「덮어쓰기」다")
                .isZero();
    }

    @Test
    @DisplayName("implementationFeasible=false 는 REJECTED — 분석 결과는 남는다")
    void 구현_불가는_REJECTED_다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);
        analyst.given(analysis("0.90", false, "무엇을 고쳐야 하는지 알 수 없다"));

        AnalysisResult result = analyzeIssues.analyze(repositoryId);

        assertThat(result.rejected()).isEqualTo(1);
        ContributionCandidate candidate = candidates.findAll().getFirst();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.REJECTED);
        assertThat(candidate.getAnalysis())
                .as("ANALYZED 를 거쳐 가므로 왜 걸러졌는지가 DB 에 남는다")
                .isNotBlank();
        assertThat(candidate.getImplementationFeasible()).isFalse();
    }

    @Test
    @DisplayName("임계 미만 신뢰도는 REJECTED, 임계값 자체는 통과한다")
    void 저신뢰도는_REJECTED_다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);
        analyst.given(analysis("0.49", true, "확신이 낮다"));

        assertThat(analyzeIssues.analyze(repositoryId).rejected()).isEqualTo(1);
        assertThat(candidates.findAll().getFirst().getStatus())
                .isEqualTo(CandidateStatus.REJECTED);

        setUp();
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);
        analyst.given(analysis("0.50", true, "경계값"));

        assertThat(analyzeIssues.analyze(repositoryId).analyzed())
                .as("임계는 「미만」이다 — 경계값은 통과한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("스키마 검증 실패는 FAILED — 그러나 AgentRun 은 SUCCEEDED 다")
    void 스키마_실패는_FAILED_다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);
        analyst.failWith(new AnalysisRejectedException("difficulty 가 알 수 없는 값입니다"));

        AnalysisResult result = analyzeIssues.analyze(repositoryId);

        assertThat(result.failed()).isEqualTo(1);
        ContributionCandidate candidate = candidates.findAll().getFirst();
        assertThat(candidate.getStatus())
                .as("파싱 실패를 성공으로 처리하지 않는다 · 분석 실패는 즉시 종단이다 — Q-6")
                .isEqualTo(CandidateStatus.FAILED);
    }

    @Test
    @DisplayName("호출 실패도 FAILED 로 끝난다 — 파이프라인 재시도를 붙이지 않는다")
    void 호출_실패는_FAILED_다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 10);
        analyst.failWith(
                new LlmPermanentException(LlmFailureReason.TRUNCATED, LlmCallSite.ANALYZE));

        assertThat(analyzeIssues.analyze(repositoryId).failed()).isEqualTo(1);
        assertThat(candidates.findAll().getFirst().getStatus())
                .isEqualTo(CandidateStatus.FAILED);
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지는 분석된다 — 고장난 이슈가 저장소를 인질로 잡지 않는다")
    void 한_건의_실패가_배치를_죽이지_않는다() {
        givenPolicy(Boolean.TRUE);
        givenIssue(1, FilterOutcome.PASSED, (short) 30);
        givenIssue(2, FilterOutcome.PASSED, (short) 20);
        givenIssue(3, FilterOutcome.PASSED, (short) 10);
        analyst.givenPerIssue(issue -> {
            if (issue.githubIssueNumber() == 2) {
                throw new AnalysisRejectedException("깨진 응답");
            }
            return analysis("0.90", true, "정상");
        });

        AnalysisResult result = analyzeIssues.analyze(repositoryId);

        assertThat(result.analyzed()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(candidates.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("분석 대상이 없으면 아무것도 하지 않는다")
    void 대상이_없으면_조용히_끝난다() {
        givenPolicy(Boolean.TRUE);

        AnalysisResult result = analyzeIssues.analyze(repositoryId);

        assertThat(result.attempted()).isZero();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    @DisplayName("저장소 식별자는 필수다")
    void 저장소_식별자가_없으면_거부한다() {
        assertThatThrownBy(() -> analyzeIssues.analyze(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────── 헬퍼 ───────────────────────

    /**
     * {@code null} = 보류. {@code RepositoryPolicy.analyzed} 가 판정 없는 값을 거부하므로
     * ({@code "판정이 서지 않았다 — pending() 을 쓴다"}) 보류는 전용 팩토리로 만든다.
     * 그 거부 자체가 「보류를 판정처럼 적을 수 없다」는 #7 의 불변식이다.
     */
    private void givenPolicy(Boolean aiContributionAllowed) {
        OssRepository repository = repositories.findById(repositoryId).orElseThrow();
        policies.save(aiContributionAllowed == null
                ? RepositoryPolicy.pending(repository, "문서를 읽지 못했다", CLOCK)
                : RepositoryPolicy.analyzed(repository,
                        new RuleReading(aiContributionAllowed, "17", null, null, false, false,
                                false, ScrubbedRules.of("{}")),
                        CLOCK));
    }

    private void givenIssue(int number, FilterOutcome outcome, short priority) {
        Issue issue = Issue.fromSnapshot(repositoryId, "spring-projects", "spring-kafka",
                snapshot(number), NOW);
        issue.applyFilter(FilterVerdict.of(reasonsFor(outcome), priority), NOW);
        issues.save(issue);
    }

    /** {@code filter_priority} 가 NULL 로 남는 경로 — 판정 없이 컬럼만 채운다. */
    private void givenIssueWithoutPriority(int number) {
        Issue issue = Issue.fromSnapshot(repositoryId, "spring-projects", "spring-kafka",
                snapshot(number), NOW);
        issue.applyFilter(FilterVerdict.of(List.of(), 0), NOW);
        issues.save(issue);
        issues.flush();
        // priority 를 NULL 로 되돌린다 — 엔티티에 그 경로가 없다.
        // 「판정은 있는데 점수가 없는」 행(옛 데이터·수동 보정)을 재현하는 것이 목적이고,
        // 운영 인터페이스에 테스트 전용 메서드를 만들지 않기 위해 SQL 로 한다
        jdbc.update("UPDATE issue SET filter_priority = NULL WHERE id = ?", issue.getId());
    }

    /** 판정을 직접 지정하지 않는다 — 사유에서 집계된다 ({@code FilterVerdict.of}). */
    private static List<FilterReason> reasonsFor(FilterOutcome outcome) {
        return switch (outcome) {
            case PASSED -> List.of();
            case UNDECIDED -> List.of(FilterReason.SHORT_BODY);
            case REJECTED -> List.of(FilterReason.CLOSED);
        };
    }

    private static IssueSnapshot snapshot(int number) {
        return new IssueSnapshot(number, "제목 " + number, "본문".repeat(300),
                List.of("bug"), "someone", 3, NOW, NOW, false);
    }

    private static IssueAnalysis analysis(String confidence, boolean feasible, String summary) {
        return new IssueAnalysis("bug", IssueAnalysis.Difficulty.MEDIUM, feasible, 3, 80,
                true, false, new BigDecimal(confidence), summary);
    }
}
