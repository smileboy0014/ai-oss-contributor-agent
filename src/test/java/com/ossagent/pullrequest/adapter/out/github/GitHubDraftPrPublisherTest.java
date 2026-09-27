package com.ossagent.pullrequest.adapter.out.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.pullrequest.domain.BaseBranch;
import com.ossagent.pullrequest.domain.BranchName;
import com.ossagent.pullrequest.domain.DraftPrException;
import com.ossagent.pullrequest.domain.DraftPrRequest;
import com.ossagent.pullrequest.domain.ForkRef;
import com.ossagent.pullrequest.domain.OpenedPullRequest;
import com.ossagent.pullrequest.domain.PrBody;
import com.ossagent.pullrequest.domain.PrTitle;
import com.ossagent.pullrequest.domain.PublishedBranch;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 어댑터 매핑 층 — 요청 조립 · 응답 파싱 · 오류 변환 (Q-9 두 번째 층).
 *
 * <p>🔴 <b>S-2 가 실제로 전선 위에서 지켜지는지</b>를 보는 자리다. 「{@code draft} 를
 * 필드로 두지 않았다」는 <b>의도 표기</b>이고, 그것이 JSON 으로 나가는지는 Jackson 의
 * 동작에 달렸다 — 「될 것이다」로 두지 않고 <b>직렬화 결과 문자열</b>을 단언한다.
 *
 * <p>전송 계약(읽기 타임아웃 · 리다이렉트 거부)은 같은 {@code GitHubApiClient}·
 * {@code GitHubWriteClient} 조립 경로를 쓰므로 {@code GitHubTransportContractTest} 가
 * 이미 덮는다 — 같은 층을 두 번 재지 않는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitHubDraftPrPublisherTest {

    private static final String BASE = "https://api.github.test";
    private static final String FORK_OWNER = "smileboy0014";
    private static final String REPO = "spring-kafka";
    private static final RepositoryCoordinates UPSTREAM =
            RepositoryCoordinates.parse("spring-projects/" + REPO);
    private static final String PULLS = BASE + "/repos/spring-projects/" + REPO + "/pulls";

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-27T09:00:00Z"), ZoneOffset.UTC);

    private MockRestServiceServer server;
    private GitHubDraftPrPublisher publisher;

    @BeforeEach
    void setUp() {
        GitHubProperties properties = new GitHubProperties(BASE, "ghp_" + "e".repeat(30),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ZERO, 100);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();

        GitHubRateLimitBudget budget = new GitHubRateLimitBudget(clock);
        GitHubErrorTranslator translator = new GitHubErrorTranslator(clock);
        GitHubApiClient readClient = new GitHubApiClient(builder.build(),
                StaticTokenCredentials.from(properties), properties, translator, clock, budget);
        GitHubWriteClient writeClient = new GitHubWriteClient(builder.build(),
                StaticTokenCredentials.from(properties), properties, translator, budget,
                FORK_OWNER, clock);

        publisher = new GitHubDraftPrPublisher(readClient, writeClient);
    }

    // ── S-2 : draft 고정 ────────────────────────────────────────────────

    @Test
    void 생성_요청_본문에_draft_true_가_실린다_S2() {
        AtomicReference<String> sent = new AtomicReference<>();
        server.expect(once(), requestTo(PULLS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> sent.set(
                        ((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(createdPr(true), MediaType.APPLICATION_JSON));

        publisher.openDraft(request());

        // 🔴 모수 — 본문을 실제로 잡았는가. 이것이 없으면 아래 단언이 「빈 문자열에
        //    draft:false 가 없다」를 재고도 초록이 된다
        assertThat(sent.get()).as("요청 본문을 잡지 못했다면 아래 단언은 공허하다").isNotBlank();
        assertThat(sent.get())
                .as("draft 가 아닌 PR 은 검증되지 않은 AI 코드를 메인테이너 리뷰 큐에 올린다")
                .contains("\"draft\":true")
                .doesNotContain("\"draft\":false");

        server.verify();
    }

    @Test
    void payload_직렬화에_draft_true_가_포함된다_S2() throws Exception {
        // 🔴 record 가 아니라 클래스로 만든 이유가 이것이다 — 「컴포넌트가 아닌 접근자」가
        //    직렬화되는지가 애노테이션 동작에 달리는 것을 S-2 에 걸어 두지 않는다.
        //    위 테스트와 중복이 아니다: 저쪽은 어댑터가 이 payload 를 쓰는지를 보고,
        //    여기는 payload 자체의 직렬화 계약을 회귀로 고정한다
        var payload = new GitDataPayloads.DraftPullRequestRequest(
                "제목", FORK_OWNER + ":oss-agent/issue-12-x", "main", "본문");

        String json = new ObjectMapper().writeValueAsString(payload);

        assertThat(json).isNotBlank();
        assertThat(json)
                .contains("\"draft\":true")
                .contains("\"head\":\"" + FORK_OWNER + ":oss-agent/issue-12-x\"")
                .contains("\"base\":\"main\"");
        // 🔴 리뷰어·라벨은 존재 자체가 반려다 (S-2)
        assertThat(json)
                .doesNotContain("reviewers")
                .doesNotContain("assignees")
                .doesNotContain("labels");
    }

    @Test
    void payload_의_toString_이_제목과_본문을_노출하지_않는다_S4() {
        var payload = new GitDataPayloads.DraftPullRequestRequest(
                "비밀 제목", FORK_OWNER + ":oss-agent/issue-12-x", "main", "비밀 본문");

        assertThat(payload.toString())
                .doesNotContain("비밀 제목")
                .doesNotContain("비밀 본문")
                .contains("draft=true");
    }

    @Test
    void 응답이_draft_가_아니면_거부한다_S2() {
        server.expect(once(), requestTo(PULLS))
                .andRespond(withSuccess(createdPr(false), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> publisher.openDraft(request()))
                .as("우리가 보낸 것과 GitHub 이 만든 것이 다르면 그것은 사고다")
                .isInstanceOf(DraftPrException.class)
                .hasMessageContaining("draft 가 아닌");

        server.verify();
    }

    @Test
    void draft_필드가_아예_없는_응답도_거부한다_S2() {
        // 🔴 「확인할 수 없다」를 「괜찮다」로 번역하지 않는다 — 필드 부재는 기본값 false 로
        //    읽히고, 그것을 통과시키면 S-2 방어가 GitHub 의 응답 스키마에 위임된다
        String noDraftField = """
                {"number":42,"html_url":"https://github.test/pr/42",
                 "head":{"repo":{"html_url":"https://github.test/%s/%s"}}}
                """.formatted(FORK_OWNER, REPO);
        server.expect(once(), requestTo(PULLS))
                .andRespond(withSuccess(noDraftField, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> publisher.openDraft(request()))
                .isInstanceOf(DraftPrException.class);

        server.verify();
    }

    // ── 요청 조립 ───────────────────────────────────────────────────────

    @Test
    void head_는_owner_콜론_branch_이고_base_는_기준_브랜치다() {
        AtomicReference<String> sent = new AtomicReference<>();
        server.expect(once(), requestTo(PULLS))
                .andExpect(request -> sent.set(
                        ((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(createdPr(true), MediaType.APPLICATION_JSON));

        publisher.openDraft(request());

        assertThat(sent.get()).isNotBlank();
        assertThat(sent.get())
                .as("교차 저장소 PR 은 head 가 owner:branch 가 아니면 GitHub 이 422 를 준다")
                .contains("\"head\":\"" + FORK_OWNER + ":oss-agent/issue-12-x\"")
                .contains("\"base\":\"3.2.x\"");

        server.verify();
    }

    // ── 응답 파싱 ───────────────────────────────────────────────────────

    @Test
    void 생성된_PR_의_번호와_주소와_fork_주소를_읽는다_S1() {
        server.expect(once(), requestTo(PULLS))
                .andRespond(withSuccess(createdPr(true), MediaType.APPLICATION_JSON));

        OpenedPullRequest opened = publisher.openDraft(request());

        assertThat(opened.number()).isEqualTo(42);
        assertThat(opened.url()).isEqualTo("https://github.test/pr/42");
        assertThat(opened.headRef()).isEqualTo(FORK_OWNER + ":oss-agent/issue-12-x");
        // 🔴 조립하지 않고 GitHub 이 기록한 것을 읽는다 — 「어디에 썼는가」의 증거다
        assertThat(opened.forkUrl())
                .as("우리가 조립한 값은 믿음이지 관측이 아니다 — fork 이름이 {name}-1 일 수 있다")
                .isEqualTo("https://github.test/" + FORK_OWNER + "/" + REPO + "-1");

        server.verify();
    }

    @Test
    void 번호_주소_fork주소_중_하나라도_없으면_거부한다() {
        String[] incomplete = {
                // number 없음
                """
                {"draft":true,"html_url":"https://github.test/pr/42",
                 "head":{"repo":{"html_url":"https://github.test/o/r"}}}""",
                // html_url 없음
                """
                {"draft":true,"number":42,
                 "head":{"repo":{"html_url":"https://github.test/o/r"}}}""",
                // head.repo.html_url 없음
                """
                {"draft":true,"number":42,"html_url":"https://github.test/pr/42"}""",
        };

        // ⚠ MockRestServiceServer 는 첫 실제 요청 이후에 기대를 더할 수 없다.
        //    등록을 전부 마친 뒤에 호출한다
        for (String body : incomplete) {
            server.expect(once(), requestTo(PULLS))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }

        for (int i = 0; i < incomplete.length; i++) {
            assertThatThrownBy(() -> publisher.openDraft(request()))
                    .as("사람이 도달할 수 없는 PR 이 「만들어졌다」로 기록되면 종단 상태라 "
                            + "후보가 빠져나올 수 없다 (응답 %d/%d)", i + 1, incomplete.length)
                    .isInstanceOf(DraftPrException.class);
        }

        server.verify();
    }

    // ── findOpen — FR-12 ────────────────────────────────────────────────

    @Test
    void 열린_PR_이_없으면_빈_값이다() {
        server.expect(once(), requestTo(Matchers.startsWith(PULLS)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(publisher.findOpen(UPSTREAM, head())).isEmpty();

        server.verify();
    }

    @Test
    void 열린_PR_이_있으면_그것을_돌려준다_S2() {
        server.expect(once(), requestTo(Matchers.startsWith(PULLS)))
                .andRespond(withSuccess("[" + createdPr(true) + "]", MediaType.APPLICATION_JSON));

        Optional<OpenedPullRequest> found = publisher.findOpen(UPSTREAM, head());

        assertThat(found)
                .as("두 번째 PR 을 여는 것은 남의 저장소에서 스팸으로 취급된다")
                .isPresent();
        assertThat(found.orElseThrow().number()).isEqualTo(42);

        server.verify();
    }

    @Test
    void 조회_URL_에_state_open_과_head_가_실린다() {
        AtomicReference<String> uri = new AtomicReference<>();
        server.expect(once(), requestTo(Matchers.startsWith(PULLS)))
                .andExpect(request -> uri.set(request.getURI().toString()))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        publisher.findOpen(UPSTREAM, head());

        assertThat(uri.get()).isNotBlank();
        // 🔴 기본값이 open 이지만 기대지 않는다 — GitHub 이 바꾸는 날 닫힌 PR 을 「활성」으로
        //    읽어 정당한 재생성을 영영 막는다
        assertThat(uri.get()).contains("state=open");
        assertThat(uri.get()).as("head 로 좁히지 않으면 남의 PR 을 우리 것으로 읽는다")
                .contains("head=");

        server.verify();
    }

    @Test
    void 좌표나_head_가_없으면_호출하지_않고_거부한다() {
        assertThatThrownBy(() -> publisher.findOpen(null, head()))
                .isInstanceOf(DraftPrException.class);
        assertThatThrownBy(() -> publisher.findOpen(UPSTREAM, null))
                .isInstanceOf(DraftPrException.class);
        assertThatThrownBy(() -> publisher.openDraft(null))
                .isInstanceOf(DraftPrException.class);

        server.verify();   // 전송 0건
    }

    // ── 공통 ────────────────────────────────────────────────────────────

    private static PublishedBranch head() {
        ForkRef fork = ForkRef.of(new RepositoryCoordinates(FORK_OWNER, REPO), FORK_OWNER);
        return new PublishedBranch(fork, BranchName.of(12, "x"), "c0ffee", false);
    }

    private static DraftPrRequest request() {
        return new DraftPrRequest(UPSTREAM, head(), new BaseBranch("3.2.x"),
                PrTitle.forIssue(12, "Fix it"), new PrBody("본문"));
    }

    /** GitHub 이 돌려주는 PR 표현. {@code head.repo.html_url} 이 fork 이름을 실제로 바꾼 모양이다. */
    private static String createdPr(boolean draft) {
        return """
                {"number":42,"html_url":"https://github.test/pr/42","draft":%s,
                 "head":{"repo":{"html_url":"https://github.test/%s/%s-1"}}}
                """.formatted(draft, FORK_OWNER, REPO);
    }
}
