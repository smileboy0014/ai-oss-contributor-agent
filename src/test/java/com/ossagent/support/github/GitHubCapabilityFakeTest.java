package com.ossagent.support.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.issue.domain.FakeIssueSource;
import com.ossagent.issue.domain.IssuePage;
import com.ossagent.issue.domain.IssueQuery;
import com.ossagent.issue.domain.IssueSnapshot;
import com.ossagent.repository.domain.FakeRepositorySource;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryMetadata;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 능력 인터페이스가 실제로 페이크로 대체 가능한지.
 *
 * <p>이 테스트가 존재하는 이유는 능력 인터페이스의 값어치가 「페이크를 만들 수 있다」에 있기 때문이다
 * (규율 ③). 대체가 안 되는 계약이면 #7·#8 의 UseCase 테스트가 전부 대외 호출을 타게 된다.
 *
 * <p>페이크가 <b>GitHub 타입을 전혀 모른다</b>는 점도 함께 고정한다 — domain 능력에 기술이 섞였다면
 * 페이크를 만들 때 {@code support.github} 를 import 해야 했을 것이다.
 */
class GitHubCapabilityFakeTest {

    private static final RepositoryCoordinates KAFKA =
            new RepositoryCoordinates("spring-projects", "spring-kafka");

    @Test
    @DisplayName("저장소 능력을 페이크로 대체할 수 있다")
    void 저장소_능력을_페이크로_대체한다() {
        FakeRepositorySource fake = new FakeRepositorySource()
                .given(new RepositoryMetadata(KAFKA, "main", "Java", false, false, 42))
                .givenFile(KAFKA, "CONTRIBUTING.md", "DCO sign-off 가 필요합니다");

        assertThat(fake.fetchMetadata(KAFKA).defaultBranch()).isEqualTo("main");
        assertThat(fake.fetchFile(KAFKA, "CONTRIBUTING.md", null)).isPresent();
        assertThat(fake.fetchFile(KAFKA, "AGENTS.md", null))
                .as("없는 파일만 빈 값 — 페이크도 같은 계약을 지켜야 테스트가 진실을 말한다")
                .isEmpty();
        assertThat(fake.fetchedPaths()).containsExactly("CONTRIBUTING.md", "AGENTS.md");
    }

    @Test
    @DisplayName("페이크로 레이트리밋 경로를 재현할 수 있다")
    void 실패_경로를_재현한다() {
        FakeRepositorySource fake = new FakeRepositorySource()
                .failWith(new GitHubRateLimitException(403, GitHubRateLimitException.Scope.PRIMARY,
                        Instant.parse("2026-09-22T10:00:00Z"), null, "소진"));

        assertThatThrownBy(() -> fake.fetchMetadata(KAFKA))
                .isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    @DisplayName("이슈 능력을 페이크로 대체하고 조회 조건을 관찰할 수 있다")
    void 이슈_능력을_페이크로_대체한다() {
        IssueSnapshot issue = new IssueSnapshot(1, "제목", "본문", List.of("bug"), "someone", 0,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-20T00:00:00Z"), false);
        FakeIssueSource fake = new FakeIssueSource().givenIssues(issue);

        IssuePage page = fake.fetchOpenIssues(IssueQuery.firstPage(KAFKA));

        assertThat(page.issuesOnly()).containsExactly(issue);
        assertThat(fake.queries())
                .as("커서가 올바로 전달되는지는 #8 의 UseCase 테스트가 이 기록으로 검증한다")
                .hasSize(1);
    }

    @Test
    @DisplayName("더 줄 페이지가 없으면 빈 마지막 페이지를 돌려준다")
    void 페이크가_무한루프를_만들지_않는다() {
        FakeIssueSource fake = new FakeIssueSource();

        IssuePage page = fake.fetchOpenIssues(IssueQuery.firstPage(KAFKA));

        assertThat(page.issues()).isEmpty();
        assertThat(page.hasNext())
                .as("페이크가 hasNext=true 를 계속 돌려주면 수집 루프 테스트가 멈추지 않는다")
                .isFalse();
    }
}
