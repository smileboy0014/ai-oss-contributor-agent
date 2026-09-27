package com.ossagent.pullrequest.adapter.out.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ossagent.pullrequest.domain.BaseBranch;
import com.ossagent.pullrequest.domain.BranchName;
import com.ossagent.pullrequest.domain.CommitMessage;
import com.ossagent.pullrequest.domain.FileChange;
import com.ossagent.pullrequest.domain.ForkPublishException;
import com.ossagent.pullrequest.domain.ForkRef;
import com.ossagent.pullrequest.domain.PublishRequest;
import com.ossagent.pullrequest.domain.PublishedBranch;
import com.ossagent.pullrequest.domain.SyncOutcome;
import com.ossagent.pullrequest.domain.SyncedFork;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubErrorTranslator;
import com.ossagent.support.github.GitHubProperties;
import com.ossagent.support.github.GitHubRateLimitBudget;
import com.ossagent.support.github.StaticTokenCredentials;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 어댑터 매핑 층 — 요청 조립 · 응답 파싱 · 오류 변환.
 *
 * <p>네트워크를 타지 않는다. 전송 계약(타임아웃 · 리다이렉트 거부)은 {@code GitHubApiClient} 와
 * <b>같은 조립 경로</b>를 쓰므로 {@code GitHubTransportContractTest} 가 이미 덮는다 — 여기서
 * 다시 재지 않는다.
 */
class GitHubForkPublisherTest {

    private static final String BASE = "https://api.github.test";
    private static final String OWNER = "smileboy0014";
    private static final String REPO = "spring-kafka";
    private static final String FORK = BASE + "/repos/" + OWNER + "/" + REPO;
    private static final RepositoryCoordinates UPSTREAM =
            RepositoryCoordinates.parse("spring-projects/" + REPO);

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-27T09:00:00Z"), ZoneOffset.UTC);

    private MockRestServiceServer server;
    private GitHubForkPublisher publisher;

    @BeforeEach
    void setUp() {
        GitHubProperties properties = new GitHubProperties(BASE, "ghp_" + "f".repeat(30),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ZERO, 100);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();

        GitHubRateLimitBudget budget = new GitHubRateLimitBudget(clock);
        GitHubErrorTranslator translator = new GitHubErrorTranslator(clock);
        GitHubApiClient readClient = new GitHubApiClient(builder.build(),
                StaticTokenCredentials.from(properties), properties, translator, clock, budget);
        GitHubWriteClient writeClient = new GitHubWriteClient(builder.build(),
                StaticTokenCredentials.from(properties), properties, translator, budget, OWNER, clock);

        ForkPublishProperties forkProperties =
                new ForkPublishProperties(OWNER, null, null, "Someone", "s@example.com");
        publisher = new GitHubForkPublisher(readClient, writeClient, forkProperties, clock);
    }

    // ── ensureFork ──────────────────────────────────────────────────────

    @Test
    @DisplayName("이미 이 upstream 의 fork 면 재사용하고 fork 생성을 부르지 않는다")
    void 이미_있으면_재사용한다() {
        server.expect(once(), requestTo(FORK))
                .andRespond(withSuccess(forkJson(true, "spring-projects/" + REPO),
                        MediaType.APPLICATION_JSON));
        server.expect(never(), requestTo(BASE + "/repos/spring-projects/" + REPO + "/forks"));

        ForkRef fork = publisher.ensureFork(UPSTREAM);

        assertThat(fork.fullName()).isEqualTo(OWNER + "/" + REPO);
        server.verify();
    }

    @Test
    @DisplayName("같은 이름이지만 이 upstream 의 fork 가 아니면 재사용하지 않는다")
    void 같은_이름의_무관한_저장소를_재사용하지_않는다() {
        // 🔴 owner 어설션은 통과한다 — owner 가 실제로 우리다. 여기서만 잡힌다.
        server.expect(once(), requestTo(FORK))
                .andRespond(withSuccess(forkJson(false, null), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/repos/spring-projects/" + REPO + "/forks"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"full_name\":\"" + OWNER + "/" + REPO + "-1\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/repos/" + OWNER + "/" + REPO + "-1"))
                .andRespond(withSuccess(forkJson(true, "spring-projects/" + REPO),
                        MediaType.APPLICATION_JSON));

        ForkRef fork = publisher.ensureFork(UPSTREAM);

        assertThat(fork.name())
                .as("GitHub 은 이름이 충돌하면 {name}-1 을 만든다. 우리가 이름을 조립하면 영원히 못 찾는다")
                .isEqualTo(REPO + "-1");
        server.verify();
    }

    @Test
    @DisplayName("Fork 생성 응답에 full_name 이 없으면 실패로 드러낸다")
    void full_name이_없으면_실패한다() {
        server.expect(once(), requestTo(FORK)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(BASE + "/repos/spring-projects/" + REPO + "/forks"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> publisher.ensureFork(UPSTREAM))
                .isInstanceOf(ForkPublishException.class);
    }

    // ── 동기화 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("merge_type 으로 판정한다 — 상태코드가 아니다")
    void merge_type으로_판정한다() {
        server.expect(once(), requestTo(FORK + "/merge-upstream"))
                .andRespond(withSuccess("{\"merge_type\":\"none\"}", MediaType.APPLICATION_JSON));

        SyncedFork synced = publisher.syncWithUpstream(fork(), BaseBranch.main());

        assertThat(synced.outcome())
                .as("merge-upstream 은 성공 시 200 + 본문이고 「이미 최신」은 merge_type: none 이다")
                .isEqualTo(SyncOutcome.ALREADY_UP_TO_DATE);
    }

    @Test
    @DisplayName("fast-forward 는 맞춰진 것이다")
    void fast_forward는_MERGED다() {
        server.expect(once(), requestTo(FORK + "/merge-upstream"))
                .andRespond(withSuccess("{\"merge_type\":\"fast-forward\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(publisher.syncWithUpstream(fork(), BaseBranch.main()).outcome())
                .isEqualTo(SyncOutcome.MERGED);
    }

    @Test
    @DisplayName("409 는 예외가 아니라 CONFLICT 값이다")
    void 충돌은_값으로_돌아온다() {
        server.expect(once(), requestTo(FORK + "/merge-upstream"))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("{}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(publisher.syncWithUpstream(fork(), BaseBranch.main()).outcome())
                .as("예외로 올리면 호출자가 재시도 루프에서 삼킨다. 진행 판단은 #23 이 한다")
                .isEqualTo(SyncOutcome.CONFLICT);
    }

    @Test
    @DisplayName("5xx 는 값으로 뭉개지 않고 그대로 올린다")
    void 전송_실패는_값이_아니다() {
        server.expect(once(), requestTo(FORK + "/merge-upstream"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("{}"));

        assertThatThrownBy(() -> publisher.syncWithUpstream(fork(), BaseBranch.main()))
                .as("「갈라졌다」와 「못 물어봤다」는 다르다")
                .isInstanceOf(RuntimeException.class);
    }

    // ── publish ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("6단계를 조립하고 커밋 SHA 를 돌려준다")
    void publish_6단계() {
        expectBaseReads();
        server.expect(once(), requestTo(FORK + "/git/blobs"))
                .andRespond(withSuccess("{\"sha\":\"blob1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/trees"))
                .andExpect(content().string(Matchers.containsString("\"base_tree\":\"tree0\"")))
                .andRespond(withSuccess("{\"sha\":\"tree1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/commits"))
                .andExpect(content().string(Matchers.containsString("\"parents\":[\"commit0\"]")))
                .andRespond(withSuccess("{\"sha\":\"commit1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/refs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(
                        Matchers.containsString("refs/heads/oss-agent/issue-1-x")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        PublishedBranch published = publisher.publish(
                request(List.of(FileChange.modified("a.java", "class A {}")), false));

        assertThat(published.commitSha()).isEqualTo("commit1");
        assertThat(published.updated()).isFalse();
        assertThat(published.headRef()).isEqualTo(OWNER + ":oss-agent/issue-1-x");
        server.verify();
    }

    @Test
    @DisplayName("삭제된 파일은 요청 JSON 에 sha:null 로 실린다")
    void 삭제는_sha_null로_실린다() {
        expectBaseReads();
        server.expect(never(), requestTo(FORK + "/git/blobs"));
        server.expect(once(), requestTo(FORK + "/git/trees"))
                // 🔴 문자열로 본다. Jackson 이 NON_NULL 이면 필드가 통째로 빠지고
                //    GitHub 은 「건드리지 않음」으로 읽어 삭제가 조용히 누락된다
                .andExpect(content().string(Matchers.containsString("\"sha\":null")))
                .andRespond(withSuccess("{\"sha\":\"tree1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/commits"))
                .andRespond(withSuccess("{\"sha\":\"commit1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/refs"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        publisher.publish(request(List.of(FileChange.deleted("gone.java")), false));

        server.verify();
    }

    @Test
    @DisplayName("브랜치가 이미 있으면(422) 조용히 덮지 않는다")
    void 중복_브랜치를_조용히_덮지_않는다() {
        expectBaseReads();
        server.expect(once(), requestTo(FORK + "/git/blobs"))
                .andRespond(withSuccess("{\"sha\":\"blob1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/trees"))
                .andRespond(withSuccess("{\"sha\":\"tree1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/commits"))
                .andRespond(withSuccess("{\"sha\":\"commit1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/refs"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body("{}"));

        assertThatThrownBy(() -> publisher.publish(
                request(List.of(FileChange.modified("a.java", "x")), false)))
                .as("같은 브랜치에 두 번 올리는 것은 재시도라는 뜻이고, 그 판단은 호출자가 명시해야 한다")
                .isInstanceOf(ForkPublishException.class);
    }

    @Test
    @DisplayName("allowUpdate 면 PATCH 로 force 갱신한다")
    void 재시도는_force로_갱신한다() {
        expectBaseReads();
        server.expect(once(), requestTo(FORK + "/git/blobs"))
                .andRespond(withSuccess("{\"sha\":\"blob1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/trees"))
                .andRespond(withSuccess("{\"sha\":\"tree1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/commits"))
                .andRespond(withSuccess("{\"sha\":\"commit2\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/refs/heads/oss-agent/issue-1-x"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().string(Matchers.containsString("\"force\":true")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        PublishedBranch published = publisher.publish(
                request(List.of(FileChange.modified("a.java", "x")), true));

        assertThat(published.updated()).isTrue();
        server.verify();
    }

    // ── 공통 ────────────────────────────────────────────────────────────

    private void expectBaseReads() {
        server.expect(once(), requestTo(FORK + "/git/ref/heads/main"))
                .andRespond(withSuccess("{\"object\":{\"sha\":\"commit0\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FORK + "/git/commits/commit0"))
                .andRespond(withSuccess("{\"tree\":{\"sha\":\"tree0\"}}",
                        MediaType.APPLICATION_JSON));
    }

    private static String forkJson(boolean isFork, String parentFullName) {
        String parent = parentFullName == null ? ""
                : ",\"parent\":{\"full_name\":\"" + parentFullName + "\"}";
        return "{\"fork\":" + isFork + parent + "}";
    }

    private static ForkRef fork() {
        return ForkRef.of(new RepositoryCoordinates(OWNER, REPO), OWNER);
    }

    private static PublishRequest request(List<FileChange> changes, boolean allowUpdate) {
        return new PublishRequest(new SyncedFork(fork(), SyncOutcome.MERGED), BaseBranch.main(),
                BranchName.of(1, "x"),
                CommitMessage.from("Fix it", null, ContributionConstraints.unknown(), 1, null),
                changes, allowUpdate);
    }
}
