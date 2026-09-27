package com.ossagent.pullrequest.adapter.out.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.ossagent.pullrequest.adapter.out.github.GitDataPayloads.BlobRequest;
import com.ossagent.pullrequest.adapter.out.github.GitDataPayloads.CommitRequest;
import com.ossagent.pullrequest.adapter.out.github.GitDataPayloads.CreateRefRequest;
import com.ossagent.pullrequest.adapter.out.github.GitDataPayloads.MergeUpstreamRequest;
import com.ossagent.pullrequest.adapter.out.github.GitDataPayloads.TreeEntry;
import com.ossagent.pullrequest.adapter.out.github.GitDataPayloads.TreeRequest;
import com.ossagent.pullrequest.adapter.out.github.GitDataPayloads.UpdateRefRequest;
import com.ossagent.pullrequest.adapter.out.github.GitHubWriteClient.Idempotency;
import com.ossagent.pullrequest.domain.BaseBranch;
import com.ossagent.pullrequest.domain.BranchName;
import com.ossagent.pullrequest.domain.CommitIdentity;
import com.ossagent.pullrequest.domain.FileChange;
import com.ossagent.pullrequest.domain.ForkPublishException;
import com.ossagent.pullrequest.domain.ForkPublisher;
import com.ossagent.pullrequest.domain.ForkRef;
import com.ossagent.pullrequest.domain.PublishRequest;
import com.ossagent.pullrequest.domain.PublishedBranch;
import com.ossagent.pullrequest.domain.SyncOutcome;
import com.ossagent.pullrequest.domain.SyncedFork;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.ExternalAdapter;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubApiException;
import com.ossagent.support.github.GitHubRequest;
import com.ossagent.support.github.GitHubResourceNotFoundException;
import com.ossagent.support.github.GitHubResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ForkPublisher} 의 GitHub 구현 — <b>Git Data API</b>.
 *
 * <h2>왜 JGit 이 아닌가 (Q-11 재도출)</h2>
 *
 * <p>Q-1 은 GitHub 연동을 직접 구현으로 정했고 그것은 여기서도 유지된다. 다만 「직접 구현」이
 * 「JGit 으로 push」를 뜻하지는 않는다. <b>주근거는 어설션 지점</b>이다 — JGit 은 push 대상이
 * 원격 URL <b>문자열</b>이라 S-1 어설션이 URL 파싱(스킴 · {@code user@} · 포트 · {@code .git}
 * 접미사)이 되고, Git Data API 는 owner 가 <b>인자</b>라 문자열 비교 한 줄이 된다.
 * <b>유일한 방어를 정규식 위에 세우지 않는다.</b>
 *
 * <p>보조 근거 둘은 #18 의 계획에 기댄 것이라 가정으로 둔다 — 그쪽이 JGit push 금지 ArchUnit 을
 * 걸 예정이고(합쳐진 뒤에만 적색이 되는 모양), 워크스페이스를 익명 clone 해 토큰이
 * {@code .git/config} 에 앉지 않게 해 뒀다(push 는 인증이 필요해 그 전제를 다시 연다).
 *
 * <h2>절차</h2>
 *
 * <pre>
 * ensureFork   GET  /repos/{fork}/{name}          → fork 이고 parent 가 맞으면 재사용
 *              POST /repos/{upstream}/forks       → 🔴 응답의 full_name 을 쓴다
 * sync         POST /repos/{fork}/merge-upstream  → merge_type 으로 판정
 * publish      GET  git/ref/heads/{base} · git/commits/{sha}
 *              POST git/blobs × N · git/trees · git/commits        (SAFE)
 *              POST git/refs  또는  PATCH git/refs/heads/{branch}  (UNSAFE)
 * </pre>
 *
 * <p>읽기는 {@link GitHubApiClient}, 쓰기는 {@link GitHubWriteClient} 를 쓴다. 둘은
 * <b>레이트리밋 예산을 공유</b>한다 — 갈리면 한쪽이 태운 예산을 다른 쪽이 모른다.
 */
@ExternalAdapter
public class GitHubForkPublisher implements ForkPublisher {

    private static final Logger log = LoggerFactory.getLogger(GitHubForkPublisher.class);

    private final GitHubApiClient readClient;
    private final GitHubWriteClient writeClient;
    private final ForkPublishProperties properties;
    private final Clock clock;

    public GitHubForkPublisher(GitHubApiClient readClient, GitHubWriteClient writeClient,
            ForkPublishProperties properties, Clock clock) {
        this.readClient = readClient;
        this.writeClient = writeClient;
        this.properties = properties;
        this.clock = clock;
    }

    // ── Fork 확보 ───────────────────────────────────────────────────────

    @Override
    public ForkRef ensureFork(RepositoryCoordinates upstream) {
        String forkOwner = properties.owner();
        Optional<JsonNode> existing = findRepository(forkOwner, upstream.name());

        if (existing.isPresent() && isForkOf(existing.get(), upstream)) {
            ForkRef reused = ForkRef.of(new RepositoryCoordinates(forkOwner, upstream.name()),
                    forkOwner);
            log.info("Fork 재사용 upstream={} fork={}", upstream.fullName(), reused.fullName());
            return reused;
        }

        // 🔴 여기에는 두 경우가 온다 — 「없다」와 「같은 이름인데 이 upstream 의 fork 가 아니다」.
        //    둘 다 fork 생성으로 간다. 후자에서 GitHub 은 {name}-1 을 만들어 주므로,
        //    이름을 우리가 조립하면 영원히 못 찾는다 — 응답의 full_name 을 쓴다.
        if (existing.isPresent()) {
            log.warn("Fork owner 아래 같은 이름의 저장소가 있으나 이 upstream 의 fork 가 아니다 "
                    + "— 새 fork 를 만든다 upstream={} conflicting={}/{}",
                    upstream.fullName(), forkOwner, upstream.name());
        }
        return createAndAwait(upstream, forkOwner);
    }

    private ForkRef createAndAwait(RepositoryCoordinates upstream, String forkOwner) {
        JsonNode created = writeClient.createFork(upstream.owner(), upstream.name());
        String fullName = text(created, "full_name");
        if (fullName == null) {
            throw new ForkPublishException(
                    "Fork 생성 응답에 full_name 이 없습니다 upstream=" + upstream.fullName());
        }
        ForkRef fork = ForkRef.of(RepositoryCoordinates.parse(fullName), forkOwner);

        // 🔴 POST /forks 는 202 다 — 저장소가 아직 없을 수 있다. 바로 push 하면 404 가 난다.
        //    무한 대기를 만들지 않는다: 상한 안에 안 되면 실패로 드러낸다.
        Instant deadline = clock.instant().plus(properties.readyTimeout());
        while (true) {
            if (findRepository(fork.owner(), fork.name()).isPresent()) {
                log.info("Fork 생성 완료 upstream={} fork={}", upstream.fullName(), fork.fullName());
                return fork;
            }
            if (!clock.instant().plus(properties.readyPollInterval()).isBefore(deadline)) {
                throw new ForkPublishException(
                        "Fork 가 %s 안에 준비되지 않았습니다 fork=%s (github.fork.ready-timeout)"
                                .formatted(properties.readyTimeout(), fork.fullName()));
            }
            sleep(properties.readyPollInterval().toMillis());
        }
    }

    private Optional<JsonNode> findRepository(String owner, String name) {
        try {
            GitHubResponse response = readClient.get(
                    GitHubRequest.of("/repos/%s/%s".formatted(owner, name)));
            return response.hasBody() ? Optional.of(response.body()) : Optional.empty();
        } catch (GitHubResourceNotFoundException e) {
            // 🔴 404 만 「없다」다. 나머지 실패는 그대로 올린다 — 5xx·레이트리밋을
            //    「없음」으로 번역하면 이미 있는 Fork 옆에 또 하나를 만든다.
            return Optional.empty();
        }
    }

    /**
     * 🔴 <b>존재 여부가 아니라 「이 upstream 의 fork 인가」를 본다.</b>
     *
     * <p>사용자가 같은 이름의 무관한 저장소를 갖고 있을 때 그것을 재사용하면 <b>남의 것이 아니라
     * 내 것을 망가뜨린다.</b> owner 어설션은 통과하므로(owner 가 실제로 우리다) 여기서만 잡힌다.
     */
    private static boolean isForkOf(JsonNode repository, RepositoryCoordinates upstream) {
        if (!repository.path("fork").asBoolean(false)) {
            return false;
        }
        String parent = text(repository.path("parent"), "full_name");
        return parent != null && parent.equalsIgnoreCase(upstream.fullName());
    }

    // ── 동기화 ──────────────────────────────────────────────────────────

    @Override
    public SyncedFork syncWithUpstream(ForkRef fork, BaseBranch baseBranch) {
        try {
            JsonNode result = writeClient.post(fork, "merge-upstream",
                    new MergeUpstreamRequest(baseBranch.value()), Idempotency.UNSAFE);
            SyncOutcome outcome = outcomeOf(result);
            log.info("Fork 동기화 fork={} base={} outcome={}", fork.fullName(), baseBranch, outcome);
            return new SyncedFork(fork, outcome);
        } catch (GitHubApiException e) {
            // 🔴 409·422 는 「갈라졌다」이지 전송 실패가 아니다. 예외로 올리면 호출자가
            //    재시도 루프에서 삼킨다 — 값으로 돌려 #23 이 보고 판단하게 한다.
            SyncOutcome outcome = switch (e.status()) {
                case 409 -> SyncOutcome.CONFLICT;
                case 422 -> SyncOutcome.UNMERGEABLE;
                default -> null;
            };
            if (outcome == null) {
                throw e;   // 5xx·레이트리밋·권한 — 그대로 올린다
            }
            log.warn("Fork 동기화 실패 fork={} base={} status={} outcome={}",
                    fork.fullName(), baseBranch, e.status(), outcome);
            return new SyncedFork(fork, outcome);
        }
    }

    /**
     * 🔴 상태코드가 아니라 {@code merge_type} 으로 판정한다.
     *
     * <p>{@code merge-upstream} 은 성공 시 <b>200 + 본문</b>이고 「이미 최신」은 별도 코드가 아니라
     * {@code merge_type: "none"} 이다. 상태코드로 판정하면 테스트 스텁이 실제와 다른 응답을
     * 흉내내도 초록이 되어, 어댑터 매핑 층이 잡아야 할 오류를 <b>테스트가 같은 오류를 갖고 있어서</b>
     * 못 잡는다.
     */
    private static SyncOutcome outcomeOf(JsonNode result) {
        String mergeType = result == null ? null : text(result, "merge_type");
        if (mergeType == null) {
            // 본문이 없거나 모르는 모양이다. 맞춰졌다고 단정하지 않는다
            return SyncOutcome.UNMERGEABLE;
        }
        return "none".equalsIgnoreCase(mergeType) ? SyncOutcome.ALREADY_UP_TO_DATE
                : SyncOutcome.MERGED;
    }

    // ── publish ─────────────────────────────────────────────────────────

    @Override
    public PublishedBranch publish(PublishRequest request) {
        ForkRef fork = request.forkRef();
        // 🔴 시각을 한 번만 읽는다. commit 해시에 author.date 가 들어가므로 재시도마다 다시
        //    읽으면 같은 내용이 다른 sha 를 낳아 SAFE 재전송의 멱등성이 깨진다.
        Instant committedAt = clock.instant();

        String baseCommitSha = readBaseCommitSha(fork, request.baseBranch());
        String baseTreeSha = readTreeSha(fork, baseCommitSha);
        String treeSha = createTree(fork, baseTreeSha, request.changes());
        String commitSha = createCommit(fork, request, treeSha, baseCommitSha, committedAt);
        boolean updated = pointBranchAt(fork, request, commitSha);

        log.info("Fork push 완료 fork={} branch={} commit={} files={} updated={}",
                fork.fullName(), request.branchName(), commitSha, request.changes().size(), updated);
        return new PublishedBranch(fork, request.branchName(), commitSha, updated);
    }

    private String readBaseCommitSha(ForkRef fork, BaseBranch baseBranch) {
        GitHubResponse response = readClient.get(GitHubRequest.of(
                "/repos/%s/%s/git/ref/heads/%s".formatted(fork.owner(), fork.name(),
                        baseBranch.value())));
        String sha = response.hasBody() ? text(response.body().path("object"), "sha") : null;
        if (sha == null) {
            throw new ForkPublishException(
                    "기준 브랜치를 찾지 못했습니다 fork=%s base=%s".formatted(fork.fullName(), baseBranch));
        }
        return sha;
    }

    private String readTreeSha(ForkRef fork, String commitSha) {
        GitHubResponse response = readClient.get(GitHubRequest.of(
                "/repos/%s/%s/git/commits/%s".formatted(fork.owner(), fork.name(), commitSha)));
        String sha = response.hasBody() ? text(response.body().path("tree"), "sha") : null;
        if (sha == null) {
            throw new ForkPublishException(
                    "기준 커밋의 tree 를 읽지 못했습니다 fork=%s commit=%s"
                            .formatted(fork.fullName(), commitSha));
        }
        return sha;
    }

    private String createTree(ForkRef fork, String baseTreeSha, List<FileChange> changes) {
        List<TreeEntry> entries = new ArrayList<>(changes.size());
        for (FileChange change : changes) {
            if (change.deleted()) {
                // 🔴 sha=null 이 「삭제」다. 직렬화에서 빠지면 조용히 누락된다 — GitDataPayloads
                entries.add(TreeEntry.deletion(change));
                continue;
            }
            JsonNode blob = writeClient.post(fork, "git/blobs",
                    BlobRequest.utf8(change.content()), Idempotency.SAFE);
            entries.add(TreeEntry.blob(change, requireSha(blob, "blob", change.path())));
        }
        JsonNode tree = writeClient.post(fork, "git/trees",
                new TreeRequest(baseTreeSha, entries), Idempotency.SAFE);
        return requireSha(tree, "tree", null);
    }

    private String createCommit(ForkRef fork, PublishRequest request, String treeSha,
            String parentSha, Instant committedAt) {
        CommitRequest.Author author = properties.commitIdentity()
                .map(identity -> authorOf(identity, committedAt))
                .orElse(null);
        JsonNode commit = writeClient.post(fork, "git/commits",
                new CommitRequest(request.message().text(), treeSha, List.of(parentSha),
                        author, author),
                Idempotency.SAFE);
        return requireSha(commit, "commit", null);
    }

    private static CommitRequest.Author authorOf(CommitIdentity identity, Instant at) {
        return CommitRequest.Author.of(identity, at);
    }

    /**
     * @return 기존 브랜치를 덮어썼는가
     */
    private boolean pointBranchAt(ForkRef fork, PublishRequest request, String commitSha) {
        BranchName branch = request.branchName();
        if (request.allowUpdate()) {
            // force 는 Fork 안에서만 — ForkRef 를 요구하므로 upstream 을 지목할 수 없다 (S-1)
            writeClient.patch(fork, "git/refs/heads/" + branch.value(),
                    new UpdateRefRequest(commitSha, true), Idempotency.UNSAFE);
            return true;
        }
        try {
            writeClient.post(fork, "git/refs",
                    new CreateRefRequest(branch.refName(), commitSha), Idempotency.UNSAFE);
            return false;
        } catch (GitHubApiException e) {
            if (e.status() != 422) {
                throw e;
            }
            // 🔴 조용히 force 로 덮지 않는다. 같은 브랜치에 두 번 올리는 것은 재시도라는 뜻이고,
            //    그 판단은 호출자가 allowUpdate 로 명시해야 한다 (FR-7).
            throw new ForkPublishException(
                    "브랜치가 이미 있습니다 fork=%s branch=%s — 재시도라면 호출자가 allowUpdate 를 명시합니다"
                            .formatted(fork.fullName(), branch), e);
        }
    }

    // ── 삭제 ────────────────────────────────────────────────────────────

    @Override
    public void deleteBranch(ForkRef fork, BranchName branchName) {
        writeClient.delete(fork, "git/refs/heads/" + branchName.value());
        log.info("Fork 브랜치 삭제 fork={} branch={}", fork.fullName(), branchName);
    }

    // ── 공통 ────────────────────────────────────────────────────────────

    private static String requireSha(JsonNode node, String what, String path) {
        String sha = node == null ? null : text(node, "sha");
        if (sha == null) {
            throw new ForkPublishException("GitHub 이 %s 의 sha 를 돌려주지 않았습니다%s"
                    .formatted(what, path == null ? "" : " path=" + path));
        }
        return sha;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ForkPublishException("Fork 준비를 기다리는 중 중단됐습니다", e);
        }
    }
}
