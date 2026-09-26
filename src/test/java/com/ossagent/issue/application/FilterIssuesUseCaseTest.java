package com.ossagent.issue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.issue.adapter.out.persistence.IssueJpaRepository;
import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.issue.domain.FilterReason;
import com.ossagent.issue.domain.Issue;
import com.ossagent.issue.domain.IssueSnapshot;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 판정이 <b>DB 에 실제로 남는가</b>를 고정한다 — #9.
 *
 * <p>🔴 반환 객체를 단언하지 않는다. {@code open-in-view: false} 라 트랜잭션이 걸리지
 * 않으면 조회해 온 엔티티가 detached 가 되고 변경이 flush 없이 사라지는데,
 * <b>반환값과 로그는 정상으로 보인다.</b> #7 에서 테스트 20건이 전부 초록인 채로
 * DB 만 옛날 값이었다. 그래서 여기서는 <b>DB 에서 다시 읽어</b> 단언한다.
 *
 * <p>⚠ 클래스에 {@code @Transactional} 을 붙이지 않는다 — 같은 영속성 컨텍스트를
 * 공유하면 flush 되지 않은 것도 보여 같은 이유로 거짓 통과한다.
 */
@AgentIntegrationTest
@Testcontainers
class FilterIssuesUseCaseTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final Long REPOSITORY_ID = 1L;
    private static final Long OTHER_REPOSITORY_ID = 2L;

    @Autowired
    private FilterIssuesUseCase filterIssues;

    @Autowired
    private IssueJpaRepository issues;

    @Autowired
    private IssueFilterProperties properties;

    @BeforeEach
    void setUp() {
        issues.deleteAll();
    }

    @Test
    void 판정이_DB_에_남는다() {
        save(1, longBody(), List.of("good first issue"), 0);

        filterIssues.filter(REPOSITORY_ID);

        Issue reloaded = reload(1);
        assertThat(reloaded.getFilterResult()).isEqualTo("PASSED");
        assertThat(reloaded.getFilterJudgedAt()).isNotNull();
        assertThat(reloaded.needsFilterJudgment()).isFalse();
    }

    @Test
    void 우선순위_점수가_영속된다() {
        save(1, longBody(), List.of("good first issue"), 0);

        filterIssues.filter(REPOSITORY_ID);

        assertThat(reload(1).getFilterPriority())
                .as("""
                        FilterVerdict 는 반환값이라 트랜잭션이 끝나면 사라진다.
                        #11 이 분석 순서를 SQL 로 정렬하려면 컬럼에 남아 있어야 한다 (FR-4).""")
                .isEqualTo((short) 100);
    }

    @Test
    void 배제_사유가_코드로_영속된다() {
        save(1, null, List.of("breaking-change"), 0);

        filterIssues.filter(REPOSITORY_ID);

        Issue reloaded = reload(1);
        assertThat(reloaded.getFilterResult()).isEqualTo("REJECTED");
        assertThat(reloaded.filterReasons())
                .containsExactly(FilterReason.BREAKING_CHANGE, FilterReason.EMPTY_BODY);
    }

    @Test
    void 판정된_이슈는_다시_판정하지_않는다() {
        save(1, longBody(), List.of("bug"), 0);
        filterIssues.filter(REPOSITORY_ID);
        Instant judgedAt = reload(1).getFilterJudgedAt();

        FilterResult second = filterIssues.filter(REPOSITORY_ID);

        assertThat(second.judged())
                .as("미판정만 읽는다 — 매 실행마다 전량을 다시 판정하면 배치가 의미가 없다")
                .isZero();
        assertThat(reload(1).getFilterJudgedAt()).isEqualTo(judgedAt);
    }

    @Test
    void 다른_저장소의_이슈는_건드리지_않는다() {
        save(REPOSITORY_ID, 1, longBody(), List.of("bug"), 0);
        save(OTHER_REPOSITORY_ID, 2, longBody(), List.of("bug"), 0);

        filterIssues.filter(REPOSITORY_ID);

        assertThat(reload(OTHER_REPOSITORY_ID, 2).needsFilterJudgment()).isTrue();
    }

    @Test
    void 판정할_것이_없으면_빈_결과를_돌려준다() {
        FilterResult result = filterIssues.filter(REPOSITORY_ID);

        assertThat(result.judged()).isZero();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void 배치_크기를_넘는_이슈도_전부_판정한다() {
        int count = properties.batchSize() + 5;
        for (int i = 1; i <= count; i++) {
            save(i, longBody(), List.of("bug"), 0);
        }

        FilterResult result = filterIssues.filter(REPOSITORY_ID);

        assertThat(result.judged())
                .as("""
                        조회 조건(filter_result IS NULL)을 판정이 바로 채운다.
                        page=1 로 넘기면 판정한 행이 결과 집합에서 빠져 배치 크기만큼을 건너뛴다.""")
                .isEqualTo(count);
        assertThat(issues.countByRepositoryIdAndFilterResult(REPOSITORY_ID, "PASSED"))
                .isEqualTo(count);
    }

    @Test
    void 판정별_건수를_집계한다() {
        save(1, longBody(), List.of("bug"), 0);                       // PASSED
        save(2, "짧다", List.of(), 0);                                 // UNDECIDED
        save(3, null, List.of(), 0);                                  // REJECTED

        FilterResult result = filterIssues.filter(REPOSITORY_ID);

        assertThat(result.countOf(FilterOutcome.PASSED)).isEqualTo(1);
        assertThat(result.countOf(FilterOutcome.UNDECIDED)).isEqualTo(1);
        assertThat(result.countOf(FilterOutcome.REJECTED)).isEqualTo(1);
        assertThat(result.judged()).isEqualTo(3);
    }

    @Test
    void 로그_요약에_이슈_본문이_들어가지_않는다() {
        String secretish = "token " + "ghp_" + "A".repeat(36);
        save(1, secretish, List.of("breaking-change"), 0);

        FilterResult result = filterIssues.filter(REPOSITORY_ID);

        assertThat(result.toString())
                .as("대상 저장소 텍스트를 로그에 실으면 로그 인젝션·시크릿 유출 경로가 된다 — logging.md")
                .doesNotContain("ghp_", "token", "breaking");
    }

    // ─────────────────────────────────────────────────────────

    private void save(int number, String body, List<String> labels, int comments) {
        save(REPOSITORY_ID, number, body, labels, comments);
    }

    private void save(Long repositoryId, int number, String body, List<String> labels, int comments) {
        issues.save(Issue.fromSnapshot(repositoryId, "spring-projects", "spring-kafka",
                new IssueSnapshot(number, "제목", body, labels, "someone", comments, NOW, NOW, false),
                NOW));
    }

    private Issue reload(int number) {
        return reload(REPOSITORY_ID, number);
    }

    private Issue reload(Long repositoryId, int number) {
        return issues.findByRepositoryIdAndGithubIssueNumber(repositoryId, number).orElseThrow();
    }

    private static String longBody() {
        return "본문".repeat(300);
    }
}
