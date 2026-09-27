package com.ossagent.pullrequest.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.testing.FakeAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ForkPublisher} 의 테스트 대역 — Q-9 「능력 대역」 층.
 *
 * <p>🔴 <b>실패 모드를 재현할 수 있어야 한다.</b> 「항상 성공만 반환하는 페이크」는
 * 게이트를 검증하지 못한다 — 이 제품의 품질 축은 <b>「나쁜 결과를 걸러내는가」</b>다
 * ({@code testing-philosophy.md}).
 *
 * <p>⚠ 대역은 싱글턴이고 컨텍스트는 테스트 클래스 사이에 캐시된다. <b>누적되는 기록</b>
 * ({@link #publishedBranches()} · {@link #deletedBranches()})은 앞 테스트의 흔적을 보므로,
 * 호출 기록을 단언하는 테스트는 {@code @BeforeEach} 에서 {@link #reset()} 한다.
 */
@FakeAdapter
public class FakeForkPublisher implements ForkPublisher {

    private final List<PublishRequest> publishRequests = new ArrayList<>();
    private final List<BranchName> deletedBranches = new ArrayList<>();

    private String forkOwner = "fake-fork-owner";
    private SyncOutcome syncOutcome = SyncOutcome.ALREADY_UP_TO_DATE;
    private RuntimeException ensureForkFailure;
    private RuntimeException publishFailure;
    private String commitSha = "fake-commit-sha";
    private CommitIdentity commitIdentity;
    private RuntimeException syncFailure;

    // ── 대역 조작 ───────────────────────────────────────────────────────

    public void givenForkOwner(String owner) {
        this.forkOwner = owner;
    }

    /** 커밋 서명자. 기본은 비어 있다 — sign-off 가 필수인 규약에서 그 부재가 드러나야 한다. */
    public void givenCommitIdentity(CommitIdentity identity) {
        this.commitIdentity = identity;
    }

    /** 동기화 자체가 실패한다(레이트리밋·5xx). 값이 아니라 예외로 오는 경로다. */
    public void givenSyncFails(RuntimeException failure) {
        this.syncFailure = failure;
    }

    public void givenSyncOutcome(SyncOutcome outcome) {
        this.syncOutcome = outcome;
    }

    public void givenCommitSha(String sha) {
        this.commitSha = sha;
    }

    /** Fork 확보 실패 — 준비 상한 초과 · 같은 이름의 저장소가 fork 가 아님. */
    public void givenEnsureForkFails(RuntimeException failure) {
        this.ensureForkFailure = failure;
    }

    /**
     * publish 실패. 🔴 {@link UpstreamWriteAttemptException} 을 넣어
     * <b>S-1 이 발화하는 경로</b>를 소비자 층에서 재현할 수 있다.
     */
    public void givenPublishFails(RuntimeException failure) {
        this.publishFailure = failure;
    }

    public void reset() {
        publishRequests.clear();
        deletedBranches.clear();
        ensureForkFailure = null;
        publishFailure = null;
        syncOutcome = SyncOutcome.ALREADY_UP_TO_DATE;
        commitSha = "fake-commit-sha";
        commitIdentity = null;
        syncFailure = null;
    }

    // ── 기록 ────────────────────────────────────────────────────────────

    public List<PublishRequest> publishRequests() {
        return List.copyOf(publishRequests);
    }

    public List<BranchName> deletedBranches() {
        return List.copyOf(deletedBranches);
    }

    // ── 능력 ────────────────────────────────────────────────────────────

    @Override
    public ForkRef ensureFork(RepositoryCoordinates upstream) {
        if (ensureForkFailure != null) {
            throw ensureForkFailure;
        }
        return ForkRef.of(new RepositoryCoordinates(forkOwner, upstream.name()), forkOwner);
    }

    @Override
    public java.util.Optional<CommitIdentity> commitIdentity() {
        return java.util.Optional.ofNullable(commitIdentity);
    }

    @Override
    public SyncedFork syncWithUpstream(ForkRef fork, BaseBranch baseBranch) {
        if (syncFailure != null) {
            throw syncFailure;
        }
        return new SyncedFork(fork, syncOutcome);
    }

    @Override
    public PublishedBranch publish(PublishRequest request) {
        publishRequests.add(request);
        if (publishFailure != null) {
            throw publishFailure;
        }
        // 🔴 실물과 같은 어설션을 한 번 더 태운다 — 대역이 upstream 좌표를 통과시키면
        //    소비자 테스트가 S-1 위반을 초록으로 본다.
        ForkRef fork = request.forkRef();
        if (!ForkRef.sameOwner(fork.owner(), forkOwner)) {
            throw new UpstreamWriteAttemptException(
                    "쓰기 대상이 Fork 가 아닙니다: 대상 owner=%s · Fork owner=%s"
                            .formatted(fork.owner(), forkOwner));
        }
        return new PublishedBranch(fork, request.branchName(), commitSha, request.allowUpdate());
    }

    @Override
    public void deleteBranch(ForkRef fork, BranchName branchName) {
        deletedBranches.add(branchName);
    }
}
