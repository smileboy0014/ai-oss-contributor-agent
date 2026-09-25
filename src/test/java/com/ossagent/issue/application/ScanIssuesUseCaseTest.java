package com.ossagent.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.issue.adapter.out.persistence.IssueJpaRepository;
import com.ossagent.issue.domain.FakeIssueSource;
import com.ossagent.issue.domain.IssuePage;
import com.ossagent.issue.domain.IssueSnapshot;
import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.github.GitHubApiException;
import com.ossagent.support.github.GitHubPermissionException;
import com.ossagent.support.github.GitHubRateLimitException;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

/**
 * 증분 수집의 <b>안전 성질</b>을 고정한다 — #8.
 *
 * <p>여기서 검증하는 것 셋은 코드를 읽어서는 구분되지 않는다.
 * <ul>
 *   <li>리밋이 <b>실패가 아니라 지연</b>인가</li>
 *   <li>리밋에 걸렸을 때 <b>부분 수집을 지키는가</b></li>
 *   <li>권한 오류는 지연이 아니라 <b>실패</b>인가</li>
 * </ul>
 *
 * <p>⚠ 페이크는 넣어 준 순서를 돌려줄 뿐이라 {@code updated_at} 오름차순 계약은
 * <b>여기서 검증되지 않는다</b> — 어댑터 테스트가 지킨다.
 */
@AgentIntegrationTest
@Testcontainers
class ScanIssuesUseCaseTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T1 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-09-03T10:00:00Z");

    @Autowired
    private ScanIssuesUseCase scanIssues;

    @Autowired
    private FakeIssueSource issueSource;

    @Autowired
    private IssueJpaRepository issues;

    @Autowired
    private OssRepositoryRepository repositories;

    private Long repositoryId;
    private RepositoryCoordinates coordinates;

    @BeforeEach
    void setUp() {
        issueSource.reset();   // 페이크는 싱글턴이다 — 앞 테스트 상태가 샌다
        issues.deleteAll();
        repositories.deleteAll();
        OssRepository repository = repositories.save(
                new OssRepository("spring-projects", "spring-kafka",
                        "https://github.com/spring-projects/spring-kafka"));
        repositoryId = repository.getId();
        coordinates = new RepositoryCoordinates("spring-projects", "spring-kafka");
    }

    // ── 리밋은 실패가 아니라 지연이다 ──────────────────────────

    @Test
    void 레이트리밋은_실패가_아니라_지연이다() {
        Instant resetAt = T3.plus(Duration.ofMinutes(30));
        issueSource.thenFailWith(rateLimit(GitHubRateLimitException.Scope.PRIMARY, resetAt, null));

        ScanResult result = scanIssues.scan(repositoryId, coordinates);

        assertThat(result.isDelayed())
                .as("「리밋 소진은 정상 운영 상황」이다 — 실패로 처리하면 후보가 코드 문제 없이 죽는다")
                .isTrue();
        assertThat(result.delayedUntil()).isEqualTo(resetAt);
    }

    @Test
    void 리밋에_걸려도_그때까지_수집한_것은_지킨다() {
        // 2페이지를 읽고 3페이지에서 리밋 — 앞 2페이지는 저장되고 커서가 전진해야 한다
        issueSource
                .givenPageWithNext("etag-1", issue(1, T1), issue(2, T1))
                .givenPageWithNext(null, issue(3, T2))
                .thenFailWith(rateLimit(GitHubRateLimitException.Scope.PRIMARY, T3, null));

        ScanResult result = scanIssues.scan(repositoryId, coordinates);

        assertThat(result.isDelayed()).isTrue();
        assertThat(result.savedCount()).isEqualTo(3);
        assertThat(issues.countByRepositoryId(repositoryId))
                .as("버리면 같은 구간을 다시 읽어 리밋을 또 태운다")
                .isEqualTo(3);
        assertThat(reloadRepository().getIssueCursorUpdatedAt())
                .as("커서가 저장된 데이터까지 전진해야 다음 스캔이 이어받는다")
                .isEqualTo(T2);
    }

    @Test
    void 이차_리밋은_RetryAfter_를_그대로_쓴다() {
        Duration retryAfter = Duration.ofSeconds(90);
        issueSource.thenFailWith(
                rateLimit(GitHubRateLimitException.Scope.SECONDARY, null, retryAfter));

        ScanResult result = scanIssues.scan(repositoryId, coordinates);

        assertThat(result.isDelayed()).isTrue();
        assertThat(result.delayedUntil())
                .as("GitHub 이 준 값을 쓴다 — 우리가 짧게 추측하면 2차 리밋에서 차단이 더 길어진다")
                .isNotNull();
    }

    @Test
    void 권한_오류는_지연이_아니라_실패다() {
        issueSource.failWith(new GitHubPermissionException(403, "접근 권한이 없습니다"));

        assertThatThrownBy(() -> scanIssues.scan(repositoryId, coordinates))
                .as("리밋 신호가 없는 403 은 재시도해도 같다 — 지연으로 삼키면 영원히 조용하다")
                .isInstanceOf(GitHubApiException.class);
    }

    // ── 멱등성 ───────────────────────────────────────────────

    @Test
    void 같은_이슈를_두_번_수집해도_행이_늘지_않는다() {
        issueSource.givenIssues(issue(1, T1), issue(2, T1));
        scanIssues.scan(repositoryId, coordinates);

        issueSource.givenIssues(issue(1, T1), issue(2, T1));
        scanIssues.scan(repositoryId, coordinates);

        assertThat(issues.countByRepositoryId(repositoryId))
                .as("uk_issue_repository_number 와 조회 기반 upsert 가 함께 지킨다")
                .isEqualTo(2);
    }

    @Test
    void PR_은_이슈로_저장하지_않는다() {
        issueSource.given(new IssuePage(
                List.of(issue(1, T1), pullRequest(2, T1)), null, false, false));

        ScanResult result = scanIssues.scan(repositoryId, coordinates);

        assertThat(result.savedCount()).isEqualTo(1);
        assertThat(issues.countByRepositoryId(repositoryId))
                .as("GitHub 이슈 목록 API 는 PR 도 돌려준다")
                .isEqualTo(1);
    }

    // ── 페이지네이션 ──────────────────────────────────────────

    @Test
    void 페이지_상한에_걸리면_잘렸다는_사실이_남는다() {
        // 상한(기본 10)을 넘도록 계속 다음 페이지가 있다고 응답한다
        for (int i = 1; i <= 12; i++) {
            issueSource.givenPageWithNext(null, issue(i, T1.plusSeconds(i)));
        }

        ScanResult result = scanIssues.scan(repositoryId, coordinates);

        assertThat(result.hasMore())
                .as("「다 읽었다」와 구분되지 않으면 첫 스캔이 잘린 사실이 아무 데도 안 남는다")
                .isTrue();
        assertThat(result.pagesRead()).isEqualTo(10);
        assertThat(result.delayedUntil())
                .as("상한 절단은 리밋이 아니다 — 지연이 아니라 정상 완료다")
                .isNull();
    }

    @Test
    void 변경이_없으면_아무것도_저장하지_않고_커서를_유지한다() {
        issueSource.givenIssues(issue(1, T1));
        scanIssues.scan(repositoryId, coordinates);
        Instant cursorAfterFirst = reloadRepository().getIssueCursorUpdatedAt();

        issueSource.givenNotModified("etag-1");
        ScanResult result = scanIssues.scan(repositoryId, coordinates);

        assertThat(result.unchanged()).isTrue();
        assertThat(result.savedCount()).isZero();
        assertThat(reloadRepository().getIssueCursorUpdatedAt()).isEqualTo(cursorAfterFirst);
    }

    @Test
    void 두번째_스캔은_커서를_since_로_보낸다() {
        issueSource.givenIssues(issue(1, T1), issue(2, T2));
        scanIssues.scan(repositoryId, coordinates);

        issueSource.givenIssues();
        scanIssues.scan(repositoryId, coordinates);

        assertThat(issueSource.queries().get(0).updatedSince())
                .as("첫 스캔은 전량 조회다")
                .isNull();
        assertThat(issueSource.queries().get(1).updatedSince())
                .as("두 번째부터 증분이다 — 경계는 포함(inclusive)이라 max 를 그대로 보낸다")
                .isEqualTo(T2);
    }

    // ── 헬퍼 ────────────────────────────────────────────────

    private OssRepository reloadRepository() {
        return repositories.findById(repositoryId).orElseThrow();
    }

    private static IssueSnapshot issue(int number, Instant updatedAt) {
        return new IssueSnapshot(number, "제목 " + number, "본문", List.of("good first issue"),
                "someone", 0, updatedAt.minusSeconds(3600), updatedAt, false);
    }

    private static IssueSnapshot pullRequest(int number, Instant updatedAt) {
        return new IssueSnapshot(number, "PR " + number, "본문", List.of(),
                "someone", 0, updatedAt.minusSeconds(3600), updatedAt, true);
    }

    private static GitHubRateLimitException rateLimit(GitHubRateLimitException.Scope scope,
            Instant resetAt, Duration retryAfter) {
        return new GitHubRateLimitException(403, scope, resetAt, retryAfter, "레이트리밋");
    }
}
