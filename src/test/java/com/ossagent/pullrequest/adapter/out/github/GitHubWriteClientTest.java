package com.ossagent.pullrequest.adapter.out.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ossagent.pullrequest.adapter.out.github.GitHubWriteClient.Idempotency;
import com.ossagent.pullrequest.domain.ForkRef;
import com.ossagent.pullrequest.domain.UpstreamWriteAttemptException;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.github.GitHubApiException;
import com.ossagent.support.github.GitHubErrorTranslator;
import com.ossagent.support.github.GitHubProperties;
import com.ossagent.support.github.GitHubRateLimitBudget;
import com.ossagent.support.github.StaticTokenCredentials;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 🔴 <b>S-1 의 유일한 방어가 실제로 무는지.</b>
 *
 * <p>{@code ForkRef} 도 어설션을 하지만 그것은 호출부에서 일찍 드러내는 층이고,
 * 여기가 본체다 — {@code ForkRef} 를 거치지 않고 만들어진 새 호출 경로를 잡는 자리.
 */
class GitHubWriteClientTest {

    private static final String BASE_URL = "https://api.github.test";
    private static final String FORK_OWNER = "smileboy0014";
    private static final String FAKE_TOKEN = "ghp_" + "w".repeat(30);

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-27T09:00:00Z"), ZoneOffset.UTC);

    private MockRestServiceServer server;
    private GitHubWriteClient client;

    @BeforeEach
    void setUp() {
        client = clientWith(FORK_OWNER, 2);
    }

    // ── S-1 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("push 대상이 Fork 가 아니면 중단한다 — 요청이 나가지 않는다")
    void push_대상이_Fork가_아니면_중단한다_S1() {
        // 🔴 ForkRef 를 우회한 상황을 재현한다. 어설션이 ForkRef 에만 있으면
        //    이런 경로가 조용히 통과한다 — 그래서 어댑터가 다시 단언한다.
        ForkRef disguised = upstreamDisguisedAsFork();
        server.expect(never(), requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/git/refs"));

        assertThatThrownBy(() -> client.post(disguised, "git/refs", "{}", Idempotency.UNSAFE))
                .as("남의 저장소 히스토리 오염은 되돌릴 수 없다")
                .isInstanceOf(UpstreamWriteAttemptException.class)
                .hasMessageContaining("spring-projects");

        server.verify();   // 🔴 「막았다」는 요청이 나가지 않았다는 뜻이어야 한다
    }

    @Test
    @DisplayName("Fork owner 가 설정되지 않으면 모든 쓰기를 거부한다")
    void Fork_owner가_없으면_쓰기를_거부한다_S1() {
        GitHubWriteClient unset = clientWith("", 0);
        ForkRef fork = ForkRef.of(new RepositoryCoordinates(FORK_OWNER, "spring-kafka"), FORK_OWNER);

        assertThatThrownBy(() -> unset.post(fork, "git/refs", "{}", Idempotency.UNSAFE))
                .as("비교할 것이 없으면 통과가 아니라 판정 불가다")
                .isInstanceOf(UpstreamWriteAttemptException.class);
    }

    @Test
    @DisplayName("하위 경로의 상대 참조로 다른 저장소를 가리킬 수 없다")
    void 상대_참조로_접두어를_빠져나갈_수_없다_S1() {
        ForkRef fork = fork();

        // 🔴 /repos/{fork}/../../spring-projects/… 로 정규화되면 owner 어설션이
        //    참인 채로 다른 대상에 쓰게 된다 — 어설션이 우회되는 유일한 모양이다
        for (String escape : new String[] {"../../spring-projects/spring-kafka/git/refs",
                "git//refs", "./git/refs", "git/../../../x"}) {
            assertThatThrownBy(() -> client.post(fork, escape, "{}", Idempotency.UNSAFE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("createFork 만 owner 어설션을 거치지 않는다")
    void createFork만_upstream_경로로_나간다_S1() {
        server.expect(once(), requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/forks"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"full_name\":\"" + FORK_OWNER + "/spring-kafka\"}",
                        MediaType.APPLICATION_JSON));

        var created = client.createFork("spring-projects", "spring-kafka");

        assertThat(created.path("full_name").asText())
                .as("fork 생성은 upstream 히스토리를 바꾸지 않는다 — 내 계정에 저장소를 만든다")
                .isEqualTo(FORK_OWNER + "/spring-kafka");
        server.verify();
    }

    // ── 재시도 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("UNSAFE 쓰기는 5xx 에도 재전송하지 않는다")
    void 비멱등_쓰기는_재전송하지_않는다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/" + FORK_OWNER + "/spring-kafka/git/refs"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.post(fork(), "git/refs", "{}", Idempotency.UNSAFE))
                .as("응답 없는 실패는 요청이 도달했는지 알 수 없다. ref 갱신을 재전송하면 상태가 두 번 바뀐다")
                .isInstanceOf(GitHubApiException.class);

        server.verify();   // 🔴 정확히 1회. 여기가 UNSAFE 의 전부다
    }

    @Test
    @DisplayName("SAFE 쓰기는 5xx 에 재전송한다 — 내용 주소라 부작용이 없다")
    void 멱등_쓰기는_재전송한다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/" + FORK_OWNER + "/spring-kafka/git/blobs"))
                .andRespond(withServerError());
        server.expect(once(), requestTo(BASE_URL + "/repos/" + FORK_OWNER + "/spring-kafka/git/blobs"))
                .andRespond(withSuccess("{\"sha\":\"b1\"}", MediaType.APPLICATION_JSON));

        var blob = client.post(fork(), "git/blobs", "{}", Idempotency.SAFE);

        assertThat(blob.path("sha").asText()).isEqualTo("b1");
        server.verify();   // 두 번 나갔다
    }

    // ── 공통 ────────────────────────────────────────────────────────────

    private static ForkRef fork() {
        return ForkRef.of(new RepositoryCoordinates(FORK_OWNER, "spring-kafka"), FORK_OWNER);
    }

    /**
     * {@code ForkRef} 의 어설션을 통과한 뒤 좌표가 바뀐 상황을 만든다.
     *
     * <p>🔴 <b>record 생성자를 직접 부른다.</b> {@code ForkRef.of} 로는 만들 수 없는 값이고,
     * 그것이 이 테스트가 재현하려는 상황이다 — 어댑터 어설션이 없으면 이 값이 그대로 나간다.
     */
    private static ForkRef upstreamDisguisedAsFork() {
        return new ForkRef(new RepositoryCoordinates("spring-projects", "spring-kafka"));
    }

    private GitHubWriteClient clientWith(String forkOwner, int maxRetries) {
        GitHubProperties properties = new GitHubProperties(BASE_URL, FAKE_TOKEN,
                Duration.ofSeconds(1), Duration.ofSeconds(1), maxRetries, Duration.ZERO, 100);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        return new GitHubWriteClient(builder.build(), StaticTokenCredentials.from(properties),
                properties, new GitHubErrorTranslator(clock), new GitHubRateLimitBudget(clock),
                forkOwner, clock);
    }
}
