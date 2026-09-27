package com.ossagent.pullrequest.adapter.out.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.ossagent.pullrequest.domain.ForkRef;
import com.ossagent.pullrequest.domain.UpstreamWriteAttemptException;
import com.ossagent.support.github.GitHubApiException;
import com.ossagent.support.github.GitHubErrorTranslator;
import com.ossagent.support.github.GitHubProperties;
import com.ossagent.support.github.GitHubRateLimitBudget;
import com.ossagent.support.github.GitHubRetryPolicy;
import com.ossagent.support.github.GitHubCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * GitHub REST API <b>쓰기</b> 클라이언트 — S-1 의 실행체.
 *
 * <h2>🔴 이 클래스가 S-1 의 유일한 방어다</h2>
 *
 * <p>{@code safety-boundaries.md} S-1 이 못 박았듯 <b>토큰 권한으로는 막을 수 없다</b> —
 * classic PAT {@code public_repo} 는 저장소별 권한 제한이 불가능하고, 「원본에는 write 를 주지
 * 않는 토큰」이라는 것이 GitHub 에 존재하지 않는다. 읽기 클라이언트가 택한 「쓰기 메서드를
 * 만들지 않음」도 <b>강제력이 아니다</b>({@code RestClient.Builder} 가 자동설정 빈이라 아무
 * 컴포넌트나 POST 할 수 있다). 남는 것은 <b>쓰기 직전 owner 어설션</b> 하나다.
 *
 * <h2>그래서 경로를 통째로 받지 않는다</h2>
 *
 * <p>{@code post("/repos/" + owner + "/" + name + "/git/refs", body)} 처럼 조립된 문자열을
 * 받으면 어설션이 <b>문자열 파싱</b>이 되고, 호출자가 경로를 직접 만들어 우회할 수 있다.
 * 이 클래스는 {@code (owner, name, subPath)} 로 받아 <b>owner 를 단언한 뒤 자기가 조립</b>한다.
 *
 * <pre>
 *   post(fork, "git/refs", body, UNSAFE)     ✅  owner 는 ForkRef 에서만 온다
 *   post("/repos/spring-projects/…", body)   ❌  그런 메서드가 없다
 * </pre>
 *
 * <p>⚠️ {@link ForkRef} 도 어설션을 하지만 그것은 <b>호출부에서 일찍 드러내는</b> 역할이다.
 * 여기서 <b>다시</b> 단언하는 이유는 {@link ForkRef} 를 거치지 않고 만들어진 새 호출 경로를
 * 잡기 위해서다. 둘 중 하나를 지워야 한다면 {@link ForkRef} 쪽이지 이쪽이 아니다.
 *
 * <h2>비멱등 쓰기에 전송 재시도를 걸지 않는다</h2>
 *
 * <p>{@link GitHubRetryPolicy} 는 {@code GitHubTransientException}(5xx · <b>응답 없는
 * 타임아웃</b>)을 재시도하는데, 읽기 타임아웃은 <b>요청이 서버에 도달했는지 알 수 없는</b>
 * 실패다. ref 갱신·fork 생성·merge 를 그대로 재전송하면 상태가 두 번 바뀐다.
 * 그래서 호출마다 {@link Idempotency} 를 <b>인자로 받는다 — 기본값을 두지 않는다.</b>
 * 기본값이 있으면 새 쓰기가 조용히 안전한 쪽으로 분류된다.
 */
public class GitHubWriteClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubWriteClient.class);

    private static final String ACCEPT = "application/vnd.github+json";
    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String API_VERSION = "2022-11-28";

    /** 🔴 {@link #createFork} 가 쓰는 <b>리터럴</b>. 파라미터가 아니라서 다른 경로를 만들 수 없다. */
    private static final String FORKS_SUB_PATH = "forks";

    /**
     * 🔴 하위 경로 세그먼트의 <b>허용</b> 문자 — 여집합 방어.
     *
     * <h2>거부목록이었다가 바꿨다</h2>
     *
     * <p>처음에는 {@code ""}·{@code "."}·{@code ".."} <b>셋만 거부</b>했다. 안전 리뷰가
     * {@code %2e%2e}·{@code ..%2f}·{@code ..\..} 가 그 셋과 <b>문자열이 달라 통과</b>한다고
     * 짚었다 — {@code testing-philosophy.md} 「거부목록으로 방어하지 않는다」 그대로다.
     *
     * <p>🔴 <b>실측이 이것을 승격시켰다.</b> {@code RestClient} 의
     * {@code uriBuilder.path(...).build()} 가 {@code ..} 를 <b>정규화도 재인코딩도 하지 않는다</b> —
     * 세그먼트 검증을 지우고 보냈더니 URI 가 그대로 나갔다:
     *
     * <pre>
     * POST https://api.github.test/repos/{fork}/spring-kafka/../../spring-projects/spring-kafka/git/refs
     * </pre>
     *
     * <p>서버나 중간 프록시가 정규화하면 <b>owner 어설션이 참인 채로 upstream 에 쓴다.</b>
     * 이 검증은 belt-and-braces 가 아니라 <b>하중을 받는 방어</b>다.
     *
     * <p>퍼센트 인코딩 변종이 실제로 뚫리는지는 서버의 디코드 순서에 달렸고 그것은
     * <b>남의 시스템 동작</b>이다. 거기에 기대지 않고 {@code %}·{@code \}·{@code @}·{@code :}·
     * {@code ?}·{@code #} 를 <b>전부</b> 막는다 — 새 변종이 나와도 목록을 고칠 필요가 없다.
     *
     * <p>⚠️ 운영 경로는 전부 리터럴이거나 {@code BranchName}(고정 정규식)이라 이 제한이
     * 좁아서 막히는 호출은 없다.
     */
    private static final java.util.regex.Pattern SAFE_SEGMENT =
            java.util.regex.Pattern.compile("[A-Za-z0-9._~-]+");

    /**
     * 이 호출을 전송 계층이 재전송해도 되는가.
     *
     * <p>🔴 <b>기본값이 없다.</b> 호출부가 매번 고르게 한다 — 새 쓰기가 추가될 때
     * 「이건 재전송해도 되나」를 반드시 묻게 만드는 것이 목적이다.
     */
    public enum Idempotency {

        /**
         * 재전송해도 우리 쪽에 부작용이 없다 — blob · tree · commit.
         *
         * <p>근거는 <b>결과 sha 를 쓰는 것이 마지막 응답 하나뿐이고, 중간에 만들어진 객체는
         * 어떤 ref 도 가리키지 않는다</b>는 것이다. (GitHub 내부의 GC 동작을 근거로 삼지 않는다 —
         * 우리가 측정한 적 없는 남의 시스템 동작이다.)
         *
         * <p>⚠️ commit 은 해시에 {@code author.date} 가 들어가므로, 재시도마다 시각을 다시
         * 읽으면 매번 다른 sha 가 나온다. <b>날짜를 고정해야</b> 진짜로 멱등이다 —
         * {@code GitHubForkPublisher} 가 진입 시 한 번 읽어 재사용한다.
         */
        SAFE,

        /** 🔴 상태를 바꾼다 — ref 생성·갱신 · fork 생성 · merge-upstream. 재전송하지 않는다. */
        UNSAFE
    }

    private final RestClient restClient;
    private final GitHubCredentials credentials;
    private final GitHubProperties properties;
    private final GitHubErrorTranslator errorTranslator;
    private final GitHubRetryPolicy retryPolicy;
    private final GitHubRateLimitBudget budget;
    private final String forkOwner;
    private final Clock clock;

    public GitHubWriteClient(RestClient restClient, GitHubCredentials credentials,
            GitHubProperties properties, GitHubErrorTranslator errorTranslator,
            GitHubRateLimitBudget budget, String forkOwner, Clock clock) {
        this.restClient = restClient;
        this.credentials = credentials;
        this.properties = properties;
        this.errorTranslator = errorTranslator;
        this.retryPolicy = GitHubRetryPolicy.from(properties);
        this.budget = budget;
        this.forkOwner = forkOwner == null ? "" : forkOwner.trim();
        this.clock = clock;
        if (this.forkOwner.isBlank()) {
            // 🔴 기동을 막지는 않는다 — 쓰기를 한 번도 안 하는 배포(읽기 전용 스캔만)가 정상이다.
            //    다만 쓰기를 시도하는 순간 assertForkOwner 가 던진다.
            log.warn("Fork owner 가 설정되지 않았습니다 — 쓰기 호출은 전부 거부됩니다 (GITHUB_FORK_OWNER)");
        }
    }

    // ── 쓰기 ────────────────────────────────────────────────────────────

    /** {@code POST /repos/{fork}/{subPath}} — owner 어설션을 거친다. */
    public JsonNode post(ForkRef fork, String subPath, Object body, Idempotency idempotency) {
        return write(HttpMethod.POST, fork.owner(), fork.name(), subPath, body, idempotency);
    }

    /** {@code PATCH /repos/{fork}/{subPath}} — owner 어설션을 거친다. */
    public JsonNode patch(ForkRef fork, String subPath, Object body, Idempotency idempotency) {
        return write(HttpMethod.PATCH, fork.owner(), fork.name(), subPath, body, idempotency);
    }

    /**
     * {@code DELETE /repos/{fork}/{subPath}} — owner 어설션을 거친다.
     *
     * <p>브랜치 삭제도 <b>Fork 안에서만</b> 일어난다 — S-1.
     */
    public JsonNode delete(ForkRef fork, String subPath) {
        return write(HttpMethod.DELETE, fork.owner(), fork.name(), subPath, null, Idempotency.UNSAFE);
    }

    /**
     * 🔴 <b>owner 어설션을 거치지 않는 유일한 메서드</b> — {@code POST /repos/{upstream}/forks}.
     *
     * <table border="1">
     *   <caption>왜 예외인가</caption>
     *   <tr><td>upstream 히스토리를 바꾸나</td>
     *       <td>❌ <b>아니다.</b> 내 계정 아래에 저장소를 만든다. upstream 은 fork 카운트만 는다</td></tr>
     *   <tr><td>우회 경로가 되나</td>
     *       <td>❌ {@code subPath} 가 <b>리터럴</b> {@value #FORKS_SUB_PATH} 다. 파라미터가 아니다</td></tr>
     *   <tr><td>되돌릴 수 있나</td><td>✅ 내 Fork 를 지우면 된다</td></tr>
     * </table>
     *
     * <p>🔴 <b>응답의 {@code full_name} 을 호출자가 써야 한다.</b> 같은 이름이 이미 있으면
     * GitHub 은 fork 를 {@code {name}-1} 로 만든다 — 이름을 우리가 조립하면 영원히 못 찾는다.
     *
     * <p>{@link Idempotency#UNSAFE} 고정이다. 재전송하면 fork 생성 요청이 중복된다.
     */
    public JsonNode createFork(String upstreamOwner, String upstreamName) {
        // 🔴 어설션을 거치지 않고 send 를 직접 부르는 유일한 자리. 늘어나면 ArchUnit 이 잡는다.
        return send(HttpMethod.POST, upstreamOwner, upstreamName, FORKS_SUB_PATH, null,
                Idempotency.UNSAFE);
    }

    // ── 내부 ────────────────────────────────────────────────────────────

    /**
     * 🔴 <b>어설션을 거치는 경로.</b> {@link #createFork} 를 제외한 모든 쓰기가 여기를 지난다.
     *
     * <p>어설션과 전송을 두 메서드로 가른 것은 의도다 — 조건문으로 예외를 표현하면
     * 「어느 호출이 어설션을 건너뛰는가」가 <b>런타임 값</b>에 달리고, 정적으로 셀 수 없다.
     * 지금은 {@link #send} 를 직접 부르는 메서드를 세는 것으로 답이 나온다.
     */
    private JsonNode write(HttpMethod method, String owner, String name, String subPath,
            Object body, Idempotency idempotency) {
        assertForkOwner(owner);
        return send(method, owner, name, subPath, body, idempotency);
    }

    private JsonNode send(HttpMethod method, String owner, String name, String subPath,
            Object body, Idempotency idempotency) {
        String path = "/repos/%s/%s/%s".formatted(owner, name, requireSubPath(subPath));
        budget.refuseIfBudgetLow(path, properties.rateLimitThreshold());

        int completedRetries = 0;
        while (true) {
            try {
                return exchange(method, path, body);
            } catch (GitHubApiException e) {
                // 🔴 UNSAFE 는 재전송하지 않는다. 응답 없는 타임아웃은 「도달했는지 모르는」
                //    실패라, 상태를 바꾸는 호출을 다시 보내면 두 번 적용된다.
                if (idempotency == Idempotency.UNSAFE || !retryPolicy.shouldRetry(e, completedRetries)) {
                    throw e;
                }
                Duration backoff = retryPolicy.backoffFor(completedRetries);
                completedRetries++;
                log.warn("GitHub 쓰기 재시도 path={} status={} attempt={}/{} backoffMs={}",
                        path, e.status(), completedRetries, retryPolicy.maxRetries(),
                        backoff.toMillis());
                sleep(backoff);
            }
        }
    }

    /**
     * 🔴 <b>S-1 어설션.</b> 이 줄이 없으면 이 프로젝트에 원본 저장소를 지킬 방어가 하나도 없다.
     *
     * <p>비교 규칙은 {@link ForkRef#sameOwner} 하나를 쓴다 — 여기에 복제하면 두 판정이
     * 갈라지고, 갈라진 순간 「어느 쪽이 참인가」를 아무도 모른다.
     */
    private void assertForkOwner(String owner) {
        if (forkOwner.isBlank()) {
            throw new UpstreamWriteAttemptException(
                    "Fork owner 가 설정되지 않아 쓰기 대상을 판정할 수 없습니다 (github.fork.owner)");
        }
        if (!ForkRef.sameOwner(owner, forkOwner)) {
            throw new UpstreamWriteAttemptException(
                    "쓰기 대상이 Fork 가 아닙니다: 대상 owner=%s · Fork owner=%s".formatted(owner, forkOwner));
        }
    }

    private JsonNode exchange(HttpMethod method, String path, Object body) {
        long startedAt = clock.millis();
        try {
            RestClient.RequestBodySpec spec = restClient.method(method)
                    .uri(uriBuilder -> uriBuilder.path(path).build())
                    .headers(this::applyProtocolHeaders);
            if (body != null) {
                spec.body(body);
            }
            return spec.exchange((httpRequest, response) -> handle(path, response, startedAt));
        } catch (GitHubApiException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw errorTranslator.translateIoFailure(path, e);
        } catch (CancellationException e) {
            // 🔴 읽기 타임아웃의 두 번째 경로 — RestClientException 계열이 아니라 아래 catch 를
            //    빠져나간다. 읽기 클라이언트와 같은 함정이고, 새 어댑터에서 빠뜨리기 쉽다
            //    (external-deps.md 함정 기록 2026-09-25).
            throw errorTranslator.translateIoFailure(path, e);
        } catch (RestClientException e) {
            GitHubApiException unwrapped = unwrap(e);
            if (unwrapped != null) {
                throw unwrapped;
            }
            throw new GitHubApiException(GitHubApiException.NO_STATUS,
                    "GitHub 쓰기 실패 path=" + path, e);
        }
    }

    private JsonNode handle(String path,
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response, long startedAt)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        HttpHeaders headers = response.getHeaders();

        log.debug("GitHub 쓰기 path={} status={} elapsedMs={}",
                path, status.value(), clock.millis() - startedAt);
        budget.recordFrom(path, headers, properties.rateLimitThreshold());

        // 🔴 리다이렉트를 따라가지 않으므로 3xx 가 여기 온다. is3xx 는 isError 가 false 라
        //    잡지 않으면 「Moved Permanently」 본문이 정상 응답으로 둔갑한다.
        //    쓰기에서는 더 나쁘다 — 「썼다」고 오해한 채 다음 단계로 넘어간다.
        if (status.is3xxRedirection()) {
            throw new GitHubApiException(status.value(),
                    "GitHub 이 리다이렉트를 돌려줬습니다 — 대상 저장소가 이동·이름변경됐을 수 있습니다. path="
                            + path + " location=" + headers.getFirst(HttpHeaders.LOCATION));
        }
        if (status.isError()) {
            throw errorTranslator.translate(status.value(), headers, readBody(response), path);
        }
        if (status.value() == 204) {
            return null;
        }
        return response.bodyTo(JsonNode.class);
    }

    private void applyProtocolHeaders(HttpHeaders headers) {
        headers.set(HttpHeaders.ACCEPT, ACCEPT);
        headers.set(API_VERSION_HEADER, API_VERSION);
        // 🔴 헤더로만 보낸다. 쿼리 파라미터로 보내면 URL 이 담기는 모든 곳으로 유출된다 — S-4
        credentials.authorizationHeader()
                .ifPresent(value -> headers.set(HttpHeaders.AUTHORIZATION, value));
    }

    private static String requireSubPath(String subPath) {
        if (subPath == null || subPath.isBlank()) {
            throw new IllegalArgumentException("쓰기 대상 하위 경로가 비어 있습니다");
        }
        String trimmed = subPath.trim();
        if (trimmed.startsWith("/")) {
            throw new IllegalArgumentException("하위 경로는 /repos/{owner}/{name} 뒤에 붙습니다: " + trimmed);
        }
        for (String segment : trimmed.split("/", -1)) {
            if (!SAFE_SEGMENT.matcher(segment).matches() || segment.equals(".")
                    || segment.equals("..")) {
                throw new IllegalArgumentException("하위 경로 세그먼트가 허용 문자를 벗어났습니다: " + trimmed);
            }
        }
        return trimmed;
    }

    private static String readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            return StreamUtils.copyToString(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static GitHubApiException unwrap(Throwable e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof GitHubApiException gitHubApiException) {
                return gitHubApiException;
            }
        }
        return null;
    }

    private static void sleep(Duration backoff) {
        if (backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubApiException(GitHubApiException.NO_STATUS, "GitHub 재시도 대기 중 중단됐습니다", e);
        }
    }
}
