package com.ossagent.issue.adapter.out.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ossagent.issue.domain.IssuePage;
import com.ossagent.issue.domain.IssueQuery;
import com.ossagent.issue.domain.IssueSnapshot;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubErrorTranslator;
import com.ossagent.support.github.GitHubProperties;
import com.ossagent.support.github.StaticTokenCredentials;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubIssueSourceTest {

    private static final String BASE_URL = "https://api.github.test";
    private static final String ISSUES_URI = BASE_URL + "/repos/spring-projects/spring-kafka/issues";
    private static final RepositoryCoordinates KAFKA =
            new RepositoryCoordinates("spring-projects", "spring-kafka");

    private MockRestServiceServer server;
    private GitHubIssueSource source;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T09:00:00Z"), ZoneOffset.UTC);
        GitHubProperties properties = new GitHubProperties(BASE_URL, "", Duration.ofSeconds(1),
                Duration.ofSeconds(1), 0, Duration.ZERO, 100);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(),
                StaticTokenCredentials.from(properties), properties,
                new GitHubErrorTranslator(clock), clock);
        source = new GitHubIssueSource(client);
    }

    @Test
    @DisplayName("이슈를 도메인 값으로 매핑한다")
    void 이슈를_매핑한다() {
        server.expect(once(), requestTo(ISSUES_URI + "?state=open&sort=updated&direction=asc&per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{
                          "number": 3612,
                          "title": "KafkaListener 재시도 설정이 무시된다",
                          "body": "재현 절차: ...",
                          "labels": [{"name": "type: bug"}, {"name": "good first issue"}],
                          "user": {"login": "someone"},
                          "comments": 3,
                          "created_at": "2026-09-01T10:00:00Z",
                          "updated_at": "2026-09-20T11:30:00Z"
                        }]
                        """, MediaType.APPLICATION_JSON));

        IssuePage page = source.fetchOpenIssues(IssueQuery.firstPage(KAFKA));

        assertThat(page.issues()).hasSize(1);
        IssueSnapshot issue = page.issues().get(0);
        assertThat(issue.number()).isEqualTo(3612);
        assertThat(issue.labels()).containsExactly("type: bug", "good first issue");
        assertThat(issue.hasLabel("GOOD FIRST ISSUE")).isTrue();
        assertThat(issue.author()).isEqualTo("someone");
        assertThat(issue.commentCount()).isEqualTo(3);
        assertThat(issue.updatedAt()).isEqualTo(Instant.parse("2026-09-20T11:30:00Z"));
        assertThat(issue.isIssue()).isTrue();
    }

    @Test
    @DisplayName("이슈 목록에 섞여 오는 PR 을 이슈로 취급하지 않는다")
    void PR을_이슈로_취급하지_않는다() {
        server.expect(once(), requestTo(ISSUES_URI + "?state=open&sort=updated&direction=asc&per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [
                          {"number": 1, "title": "진짜 이슈", "updated_at": "2026-09-20T11:30:00Z"},
                          {"number": 2, "title": "남의 PR", "updated_at": "2026-09-20T12:00:00Z",
                           "pull_request": {"url": "https://api.github.com/repos/o/n/pulls/2"}}
                        ]
                        """, MediaType.APPLICATION_JSON));

        IssuePage page = source.fetchOpenIssues(IssueQuery.firstPage(KAFKA));

        assertThat(page.issues())
                .as("PR 도 함께 돌려주는 것이 이슈 목록 API 의 동작이다 — 버리지 않고 표시만 한다")
                .hasSize(2);
        assertThat(page.issuesOnly())
                .as("걸러내지 않으면 남의 PR 을 구현 파이프라인에 태운다")
                .hasSize(1)
                .allSatisfy(issue -> assertThat(issue.number()).isEqualTo(1));
    }

    @Test
    @DisplayName("Link 헤더로 다음 페이지 여부를 안다")
    void 다음_페이지를_Link_헤더로_판단한다() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.LINK,
                "<https://api.github.test/repos/spring-projects/spring-kafka/issues?page=2>; rel=\"next\", "
                        + "<https://api.github.test/repos/spring-projects/spring-kafka/issues?page=9>; rel=\"last\"");
        server.expect(once(), requestTo(ISSUES_URI + "?state=open&sort=updated&direction=asc&per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).headers(headers));

        assertThat(source.fetchOpenIssues(IssueQuery.firstPage(KAFKA)).hasNext())
                .as("본문 길이로 추측하면 마지막 페이지가 꽉 찬 경우 빈 페이지를 한 번 더 부른다")
                .isTrue();
    }

    @Test
    @DisplayName("Link 헤더가 없으면 다음 페이지가 없다")
    void Link_헤더가_없으면_마지막_페이지다() {
        server.expect(once(), requestTo(ISSUES_URI + "?state=open&sort=updated&direction=asc&per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(source.fetchOpenIssues(IssueQuery.firstPage(KAFKA)).hasNext()).isFalse();
    }

    @Test
    @DisplayName("증분 조회 조건을 쿼리로 내보낸다")
    void 증분_조회_조건을_전달한다() {
        Instant since = Instant.parse("2026-09-20T00:00:00Z");
        server.expect(once(), queryParam("since", since.toString()))
                .andExpect(queryParam("state", "open"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        source.fetchOpenIssues(IssueQuery.firstPage(KAFKA).updatedSince(since));

        server.verify();
    }

    @Test
    @DisplayName("304 는 「이슈가 없다」가 아니라 「바뀐 것이 없다」다")
    void 변화_없음을_빈_결과와_구분한다() {
        server.expect(once(), requestTo(ISSUES_URI + "?state=open&sort=updated&direction=asc&per_page=100&page=1"))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        IssuePage page = source.fetchOpenIssues(IssueQuery.firstPage(KAFKA).withEtag("\"abc\""));

        assertThat(page.unchanged())
                .as("구분하지 않으면 증분 수집이 기존 후보를 사라진 것으로 판단한다")
                .isTrue();
        assertThat(page.issues()).isEmpty();
        assertThat(page.etag()).isEqualTo("\"abc\"");
    }

    @Test
    @DisplayName("라벨이 문자열로 와도 읽는다")
    void 문자열_라벨도_읽는다() {
        server.expect(once(), requestTo(ISSUES_URI + "?state=open&sort=updated&direction=asc&per_page=100&page=1"))
                .andRespond(withSuccess(
                        "[{\"number\":1,\"labels\":[\"bug\"],\"updated_at\":\"2026-09-20T11:30:00Z\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(source.fetchOpenIssues(IssueQuery.firstPage(KAFKA)).issues().get(0).labels())
                .containsExactly("bug");
    }

    @Test
    @DisplayName("본문이 없어도 매핑에 실패하지 않는다")
    void 본문이_없는_이슈를_견딘다() {
        server.expect(once(), requestTo(ISSUES_URI + "?state=open&sort=updated&direction=asc&per_page=100&page=1"))
                .andRespond(withSuccess("[{\"number\":7,\"updated_at\":\"2026-09-20T11:30:00Z\"}]",
                        MediaType.APPLICATION_JSON));

        IssueSnapshot issue = source.fetchOpenIssues(IssueQuery.firstPage(KAFKA)).issues().get(0);

        assertThat(issue.body()).isEmpty();
        assertThat(issue.labels()).isEmpty();
    }

    @Test
    @DisplayName("🔴 updated_at 오름차순을 실제로 요청한다 — 증분 수집의 전제다")
    void 오름차순_정렬을_요청한다() {
        // IssueSource 계약이 보장하는 것이고, 스캐너의 커서 전진이 여기에 기댄다.
        //
        // 스캐너는 리밋에 걸리면 앞 N 페이지만 읽고 「본 것의 최대 updatedAt」으로 커서를
        // 전진시킨다. 그게 안전한 유일한 이유는 앞 페이지가 가장 오래된 것들이어서
        // 안 읽은 구간이 뒤에 남기 때문이다.
        //
        // ⚠ GitHub 기본값은 desc 다. 내림차순으로 바뀌면 앞 N 페이지가 「가장 최근」이 되고
        //   커서를 거기로 전진시키는 순간 안 읽은 오래된 이슈 전부를 영구히 건너뛴다.
        //
        // ⚠ 페이크로는 이 위반이 절대 잡히지 않는다 — 넣어 준 순서를 돌려줄 뿐이다.
        //   실제로 보내는 쿼리 파라미터를 고정하는 이 테스트가 유일한 방어다.
        server.expect(once(), queryParam("sort", "updated"))
                .andExpect(queryParam("direction", "asc"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        source.fetchOpenIssues(IssueQuery.firstPage(new RepositoryCoordinates("spring-projects", "spring-kafka")));

        server.verify();
    }
}
