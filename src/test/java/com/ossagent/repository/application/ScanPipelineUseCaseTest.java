package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.FakeIssueAnalyst;
import com.ossagent.issue.adapter.out.persistence.IssueJpaRepository;
import com.ossagent.issue.domain.FakeIssueSource;
import com.ossagent.issue.domain.IssueSnapshot;
import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.adapter.out.persistence.RepositoryPolicyRepository;
import com.ossagent.repository.domain.FakeContributionRuleInterpreter;
import com.ossagent.repository.domain.FakePolicyDocumentSource;
import com.ossagent.repository.domain.FakeRepositorySource;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryMetadata;
import com.ossagent.repository.domain.RepositoryPolicy;
import com.ossagent.repository.domain.RuleReading;
import com.ossagent.repository.domain.ScrubbedRules;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 파이프라인 전체 — <b>S-5·S-6 이 이 클래스의 절반</b>이다.
 *
 * <p>🔴 이 PR 이 <b>자동 경로를 처음 만든다.</b> 지금까지 S-6 의 「스케줄러가 끝까지
 * 자동으로 흘려보내지 않는다」는 <b>스케줄러가 없어서</b> 지켜지고 있었다.
 *
 * <p>⚠ 반환값이 아니라 <b>DB 에서 다시 읽어</b> 단언한다 — #7 에서 테스트 20건이 초록인 채
 * DB 만 옛날 값이었던 전례가 있다.
 */
@AgentIntegrationTest
@Testcontainers
class ScanPipelineUseCaseTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired
    private ScanPipelineUseCase pipeline;

    @Autowired
    private FakeRepositorySource repositorySource;

    @Autowired
    private FakePolicyDocumentSource documents;

    @Autowired
    private FakeContributionRuleInterpreter interpreter;

    @Autowired
    private FakeIssueSource issueSource;

    @Autowired
    private FakeIssueAnalyst analyst;

    @Autowired
    private OssRepositoryRepository repositories;

    @Autowired
    private RepositoryPolicyRepository policies;

    @Autowired
    private IssueJpaRepository issues;

    @Autowired
    private ContributionCandidateRepository candidates;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long repositoryId;
    private RepositoryCoordinates coordinates;

    @BeforeEach
    void setUp() {
        // 대역은 싱글턴이고 컨텍스트가 테스트 클래스 사이에 캐시된다
        repositorySource.reset();
        documents.reset();
        interpreter.reset();
        issueSource.reset();
        analyst.reset();

        candidates.deleteAll();
        issues.deleteAll();
        policies.deleteAll();

        String name = "spring-kafka-" + System.nanoTime();
        coordinates = new RepositoryCoordinates("spring-projects", name);
        repositoryId = repositories.save(new OssRepository(
                "spring-projects", name, "https://github.com/spring-projects/" + name)).getId();
        repositorySource.given(new RepositoryMetadata(coordinates, "main", "Java", false, false, 0));
    }

    // ─────────────────────── 🔴 S-6 — 승인 지점 ───────────────────────

    @Test
    @DisplayName("파이프라인은 ANALYZED 까지만 간다 — SELECTED 로 자동 전이하지 않는다 S6")
    void 파이프라인은_ANALYZED_까지만_간다_S6() {
        givenAllowedPolicy();
        issueSource.givenIssues(issue(1), issue(2));

        pipeline.run(repositoryId);

        assertThat(candidates.findAll())
                .isNotEmpty()
                .allSatisfy(candidate -> {
                    assertThat(candidate.getStatus())
                            .as("자동 경로가 사람의 선택 지점을 넘어서면 제품 정의가 무너진다")
                            .isIn(CandidateStatus.ANALYZED, CandidateStatus.REJECTED);
                    assertThat(candidate.getSelectedAt()).isNull();
                });
    }

    // ─────────────────────── 🔴 S-5 — 규약 게이트 ───────────────────────

    @Test
    @DisplayName("금지 저장소는 수집조차 하지 않는다 — GitHub 호출 0회 S5")
    void 금지_저장소는_수집도_하지_않는다_S5() {
        givenPolicy(Boolean.FALSE);

        ScanPipelineResult result = pipeline.run(repositoryId);

        assertThat(result.skipReason())
                .isEqualTo(ScanTarget.SkipReason.CONTRIBUTION_FORBIDDEN);
        assertThat(issueSource.callCount())
                .as("반영할 수 없는 판정에 레이트리밋을 쓰지 않는다 — #7 이 세운 원칙")
                .isZero();
        assertThat(candidates.findAll()).isEmpty();
    }

    @Test
    @DisplayName("🔴 보류 저장소도 수집하지 않는다 — 보류는 허용이 아니다 S5")
    void 보류_저장소는_수집하지_않는다_S5() {
        policies.save(RepositoryPolicy.pending(
                repositories.findById(repositoryId).orElseThrow(), "문서를 읽지 못했다", CLOCK));

        ScanPipelineResult result = pipeline.run(repositoryId);

        assertThat(result.skipReason())
                .isEqualTo(ScanTarget.SkipReason.POLICY_UNDETERMINED);
        assertThat(issueSource.callCount()).isZero();
    }

    @Test
    @DisplayName("🔴 보류는 스캔을 반복해도 자동으로 풀리지 않는다 S5")
    void 보류는_자동으로_풀리지_않는다_S5() {
        policies.save(RepositoryPolicy.pending(
                repositories.findById(repositoryId).orElseThrow(), "문서를 읽지 못했다", CLOCK));

        pipeline.run(repositoryId);
        pipeline.run(repositoryId);
        pipeline.run(repositoryId);

        assertThat(policies.findByRepositoryId(repositoryId).orElseThrow()
                .isAiContributionUndetermined())
                .as("「기다리면 통과」가 되면 게이트가 아니다 — 사람이 푼다 (Q-8 확정 ②)")
                .isTrue();
        assertThat(candidates.findAll()).isEmpty();
    }

    // ─────────────────────── FR-0 — 정책 보장 ───────────────────────

    @Test
    @DisplayName("정책이 없으면 먼저 분석한다 — 없으면 파이프라인이 영원히 막힌다")
    void 정책이_없으면_먼저_분석한다() {
        // 정책을 만들어 두지 않는다. 규약 문서는 없는 것으로(404 → 허용, Q-8 확정 ①)
        documents.givenAbsent("CONTRIBUTING.md");
        issueSource.givenIssues(issue(1));

        pipeline.run(repositoryId);

        assertThat(policies.findByRepositoryId(repositoryId))
                .as("이 단계가 없으면 S-5 게이트가 NOT_ANALYZED 로 영원히 차단한다")
                .isPresent();
        assertThat(candidates.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("🔴 정책이 이미 있으면 다시 분석하지 않는다 — 매 주기 LLM 을 태우지 않는다")
    void 정책이_있으면_재분석하지_않는다() {
        givenAllowedPolicy();
        issueSource.givenIssues(issue(1));

        pipeline.run(repositoryId);

        assertThat(interpreter.calls())
                .as("analyze() 는 허용이면 재분석한다 — 「없을 때만」 부르지 않으면 토큰이 샌다")
                .isEmpty();
        assertThat(documents.calls()).isEmpty();
    }

    // ─────────────────────── 파이프라인 본류 ───────────────────────

    @Test
    @DisplayName("수집 → 필터 → 분석이 DB 에 남는다")
    void 세_단계가_DB_에_남는다() {
        givenAllowedPolicy();
        issueSource.givenIssues(issue(1), issue(2), issue(3));

        ScanPipelineResult result = pipeline.run(repositoryId);

        assertThat(result.issuesSaved()).isEqualTo(3);
        assertThat(result.issuesJudged()).isEqualTo(3);
        assertThat(issues.countByRepositoryId(repositoryId)).isEqualTo(3);
        assertThat(candidates.findAll()).isNotEmpty();
    }

    @Test
    @DisplayName("수집 실패는 단계를 실어 던진다 — 예외 원문은 싣지 않는다 S4")
    void 수집_실패는_단계를_실어_던진다_S4() {
        givenAllowedPolicy();
        issueSource.failWith(new IllegalStateException("토큰 ghp_xxx 로 호출 실패"));

        assertThatThrownBy(() -> pipeline.run(repositoryId))
                .isInstanceOfSatisfying(ScanStageFailedException.class, e -> {
                    assertThat(e.stage()).isEqualTo(ScanExecutionState.Stage.SCAN);
                    assertThat(e.failureType())
                            .as("진행 조회로 나가는 값이다 — 클래스 이름만")
                            .isEqualTo("IllegalStateException");
                });
    }

    @Test
    @DisplayName("🔴 트랜잭션 안에서 부르면 거부한다 — 대외 호출이 커넥션을 점유한다")
    void 트랜잭션_안에서는_부를_수_없다() {
        givenAllowedPolicy();

        // ⚠ 테스트 메서드에 @Transactional 을 붙여 자기호출로 부르면 프록시를 타지 않아
        //   트랜잭션이 아예 열리지 않는다 — 그러면 이 테스트가 「실패할 수 없는 테스트」가 된다.
        //   TransactionTemplate 으로 실제 트랜잭션을 연다
        assertThatThrownBy(() ->
                transactionTemplate.execute(status -> pipeline.run(repositoryId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("트랜잭션");
    }

    @Test
    @DisplayName("변경 없음(304)은 정상이다 — 실패가 아니다")
    void 변경_없음은_정상이다() {
        givenAllowedPolicy();
        issueSource.givenNotModified("etag-1");

        ScanPipelineResult result = pipeline.run(repositoryId);

        assertThat(result.issuesSaved()).isZero();
        assertThat(result.isSkipped()).isFalse();
    }

    // ─────────────────────── 헬퍼 ───────────────────────

    private void givenAllowedPolicy() {
        givenPolicy(Boolean.TRUE);
    }

    private void givenPolicy(Boolean allowed) {
        policies.save(RepositoryPolicy.analyzed(
                repositories.findById(repositoryId).orElseThrow(),
                new RuleReading(allowed, "17", null, null, false, false, false,
                        ScrubbedRules.of("{}")),
                CLOCK));
    }

    private static IssueSnapshot issue(int number) {
        return new IssueSnapshot(number, "제목 " + number, "본문".repeat(300),
                List.of("bug"), "someone", 3, NOW, NOW, false);
    }
}
