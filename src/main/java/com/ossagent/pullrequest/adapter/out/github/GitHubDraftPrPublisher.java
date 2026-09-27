package com.ossagent.pullrequest.adapter.out.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.ossagent.pullrequest.domain.DraftPrException;
import com.ossagent.pullrequest.domain.DraftPrPublisher;
import com.ossagent.pullrequest.domain.DraftPrRequest;
import com.ossagent.pullrequest.domain.OpenedPullRequest;
import com.ossagent.pullrequest.domain.PublishedBranch;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.ExternalAdapter;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubRequest;
import com.ossagent.support.github.GitHubResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DraftPrPublisher} 의 GitHub 구현 — <b>S-2 의 실행체</b>.
 *
 * <p>읽기는 {@link GitHubApiClient}, 쓰기는 {@link GitHubWriteClient} 를 쓴다.
 * {@code GitHubForkPublisher} 와 같은 구조다.
 *
 * <h2>🔴 이 클래스가 대상 저장소에 쓰는 것은 Draft PR 하나뿐이다 — FR-6</h2>
 *
 * <p>이슈 코멘트·리뷰 코멘트·라벨·리뷰어 지정 경로를 만들지 않는다. 그 사실은 주석이 아니라
 * {@code ForkPublishArchitectureTest.쓰기_엔드포인트가_화이트리스트_안이다_S2} 가 지킨다 —
 * <b>그 가드가 이 파일을 실제로 훑는지</b>가 #23 검토에서 잡힌 구멍이었고
 * (원래 {@code GitHubForkPublisher.java} 한 파일만 읽고 있었다), 스캔 범위를 이 디렉터리
 * 전체로 넓혀서 닫았다.
 */
@ExternalAdapter
public class GitHubDraftPrPublisher implements DraftPrPublisher {

    private static final Logger log = LoggerFactory.getLogger(GitHubDraftPrPublisher.class);

    private final GitHubApiClient readClient;
    private final GitHubWriteClient writeClient;

    public GitHubDraftPrPublisher(GitHubApiClient readClient, GitHubWriteClient writeClient) {
        this.readClient = readClient;
        this.writeClient = writeClient;
    }

    /**
     * {@code GET /repos/{upstream}/pulls?head={owner}:{branch}&state=open} — <b>읽기</b>다.
     *
     * <p>🔴 {@code state=open} 을 명시한다. 기본값은 {@code open} 이지만 <b>기본값에 기대면</b>
     * GitHub 이 바꾸는 날 닫힌 PR 을 「활성」으로 읽어 <b>정당한 재생성을 영영 막는다.</b>
     */
    @Override
    public Optional<OpenedPullRequest> findOpen(RepositoryCoordinates upstream,
            PublishedBranch head) {

        if (upstream == null) {
            throw new DraftPrException("원본 저장소 좌표가 없습니다");
        }
        if (head == null) {
            throw new DraftPrException("head 가 없습니다");
        }
        GitHubResponse response = readClient.get(GitHubRequest
                .of("/repos/%s/%s/pulls".formatted(upstream.owner(), upstream.name()))
                .withQuery("head", head.headRef())
                .withQuery("state", "open"));

        if (!response.hasBody() || !response.body().isArray() || response.body().isEmpty()) {
            return Optional.empty();
        }
        // 같은 head 로 열린 PR 은 GitHub 이 하나만 허용한다. 배열의 첫 항목이 그것이다
        JsonNode first = response.body().get(0);
        log.info("이미 열린 PR 이 있다 upstream={} head={} prNumber={}",
                upstream.fullName(), head.headRef(), first.path("number").asInt());
        return Optional.of(toOpenedPullRequest(first, head.headRef()));
    }

    @Override
    public OpenedPullRequest openDraft(DraftPrRequest request) {
        if (request == null) {
            throw new DraftPrException("PR 생성 요청이 없습니다");
        }
        var payload = new GitDataPayloads.DraftPullRequestRequest(
                request.title().value(),
                request.headRef(),
                request.base().value(),
                request.body().value());

        JsonNode created = writeClient.createDraftPullRequest(request.upstream(), payload);
        if (created == null) {
            throw new DraftPrException(
                    "PR 생성 응답이 비어 있습니다 upstream=" + request.upstream().fullName());
        }

        // 🔴 우리가 보낸 것과 GitHub 이 만든 것이 다르면 그것은 사고다 — S-2.
        //    「draft 로 요청했으니 draft 일 것이다」에 기대지 않는다. 값으로 바꾸기 전에 본다.
        //    ⚠ 필드가 없으면(path 가 missing) asBoolean 은 false 다 — 그것도 거부다.
        //      「확인할 수 없다」를 「괜찮다」로 번역하지 않는다
        if (!created.path("draft").asBoolean(false)) {
            int number = created.path("number").asInt();
            throw new DraftPrException(
                    ("GitHub 이 draft 가 아닌 PR 을 만들었습니다 — 즉시 사람이 닫아야 합니다. "
                            + "upstream=%s prNumber=%d").formatted(
                            request.upstream().fullName(), number));
        }

        OpenedPullRequest opened = toOpenedPullRequest(created, request.headRef());
        // ⚠ 제목·본문을 남기지 않는다 (S-4). 번호·URL 은 우리가 이어받을 식별자다
        log.info("Draft PR 생성 upstream={} head={} base={} prNumber={} prUrl={}",
                request.upstream().fullName(), request.headRef(), request.base(),
                opened.number(), opened.url());
        return opened;
    }

    private static OpenedPullRequest toOpenedPullRequest(JsonNode node, String headRef) {
        int number = node.path("number").asInt();
        String url = node.path("html_url").asText(null);
        // 🔴 조립하지 않고 GitHub 이 기록한 것을 읽는다 — fork 이름이 {name}-1 일 수 있다
        String forkUrl = node.path("head").path("repo").path("html_url").asText(null);
        if (number < 1 || url == null || url.isBlank() || forkUrl == null || forkUrl.isBlank()) {
            // 🔴 응답을 못 읽었는데 값을 만들면, 사람이 도달할 수 없는 PR 이 「만들어졌다」로
            //    기록된다. 종단 상태라 그 후보는 빠져나올 수 없다
            throw new DraftPrException(
                    "PR 응답에서 번호·주소·head 저장소를 읽지 못했습니다 head=" + headRef);
        }
        return new OpenedPullRequest(number, url, headRef, forkUrl);
    }
}
