package com.ossagent.pullrequest.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.testing.FakeAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link DraftPrPublisher} 의 테스트 대역 — Q-9 「능력 대역」 층.
 *
 * <p>🔴 <b>실패 모드를 재현할 수 있어야 한다.</b> 「항상 성공만 반환하는 페이크」는 게이트를
 * 검증하지 못한다 — 이 제품의 품질 축은 <b>「나쁜 결과를 걸러내는가」</b>다
 * ({@code testing-philosophy.md}). 여기서 재현해야 하는 실패는 둘이다.
 *
 * <table border="1">
 *   <caption>재현 대상</caption>
 *   <tr><th>모드</th><th>조작</th><th>무엇을 검증하게 하나</th></tr>
 *   <tr><td>이미 열린 PR 이 있다</td><td>{@link #givenAlreadyOpen}</td>
 *       <td>FR-12 — <b>두 번째 PR 을 만들지 않는다</b>(S-2). 소비자가
 *           {@link #openDraftRequests()} 가 비어 있음을 단언할 수 있어야 한다</td></tr>
 *   <tr><td>생성이 실패한다</td><td>{@link #givenOpenDraftFails}</td>
 *       <td>FR-9 — <b>대외 실패 시 전이가 커밋되지 않는다</b></td></tr>
 * </table>
 *
 * <h2>🔴 S-1·S-2 를 여기서 느슨하게 만들지 않는다</h2>
 *
 * <p>{@code FakeForkPublisher} 는 실물과 같은 owner 어설션을 한 번 더 태운다 — 대역이
 * upstream 좌표를 통과시키면 소비자 테스트가 S-1 위반을 초록으로 보기 때문이다.
 * 여기서는 그 어설션을 <b>{@link DraftPrRequest} 의 compact 생성자가 이미</b> 하고
 * (head 가 upstream 이면 {@link UpstreamWriteAttemptException}), 이 대역은 그 타입을
 * 인자로만 받으므로 <b>우회할 자리가 없다.</b> 복제하면 두 판정이 갈라진다.
 *
 * <p>같은 이유로 <b>「draft 가 아닌 결과」를 만드는 조작을 두지 않는다.</b>
 * {@link OpenedPullRequest} 에 그 자리가 없고, 대역이 그것을 표현할 수 있게 만들면
 * 소비자 쪽에 「draft 면 …, 아니면 …」 분기가 생긴다 — S-2 는 분기가 아니라 단일값이다.
 *
 * <p>⚠ 대역은 싱글턴이고 컨텍스트는 테스트 클래스 사이에 캐시된다. <b>누적되는 기록</b>
 * ({@link #openDraftRequests()} · {@link #findOpenQueries()})은 앞 테스트의 흔적을 보므로,
 * 호출 기록을 단언하는 테스트는 {@code @BeforeEach} 에서 {@link #reset()} 한다.
 */
@FakeAdapter
public class FakeDraftPrPublisher implements DraftPrPublisher {

    private static final int DEFAULT_PR_NUMBER = 4242;

    private final List<DraftPrRequest> openDraftRequests = new ArrayList<>();
    private final List<RepositoryCoordinates> findOpenQueries = new ArrayList<>();

    private OpenedPullRequest alreadyOpen;
    private RuntimeException findOpenFailure;
    private RuntimeException openDraftFailure;
    private int nextPrNumber = DEFAULT_PR_NUMBER;

    // ── 대역 조작 ───────────────────────────────────────────────────────

    /** 🔴 FR-12 — upstream 에 이 head 로 열린 PR 이 이미 있다. */
    public void givenAlreadyOpen(OpenedPullRequest opened) {
        this.alreadyOpen = opened;
    }

    /** 활성 PR 조회 자체가 실패한다 — 레이트리밋·권한 오류. */
    public void givenFindOpenFails(RuntimeException failure) {
        this.findOpenFailure = failure;
    }

    /**
     * 🔴 PR 생성이 실패한다 — {@link DraftPrException}(GitHub 이 422·응답이 draft 가 아님)
     * 또는 {@link UpstreamWriteAttemptException}(S-1 발화)을 그대로 넣어 재현한다.
     */
    public void givenOpenDraftFails(RuntimeException failure) {
        this.openDraftFailure = failure;
    }

    public void givenNextPrNumber(int prNumber) {
        this.nextPrNumber = prNumber;
    }

    public void reset() {
        openDraftRequests.clear();
        findOpenQueries.clear();
        alreadyOpen = null;
        findOpenFailure = null;
        openDraftFailure = null;
        nextPrNumber = DEFAULT_PR_NUMBER;
    }

    // ── 기록 ────────────────────────────────────────────────────────────

    /** 🔴 「만들지 않았다」를 단언하려면 비어 있음을 볼 수 있어야 한다 — FR-12. */
    public List<DraftPrRequest> openDraftRequests() {
        return List.copyOf(openDraftRequests);
    }

    public List<RepositoryCoordinates> findOpenQueries() {
        return List.copyOf(findOpenQueries);
    }

    // ── 능력 ────────────────────────────────────────────────────────────

    @Override
    public Optional<OpenedPullRequest> findOpen(RepositoryCoordinates upstream,
            PublishedBranch head) {

        if (upstream == null) {
            throw new DraftPrException("원본 저장소 좌표가 없습니다");
        }
        if (head == null) {
            throw new DraftPrException("head 가 없습니다");
        }
        findOpenQueries.add(upstream);
        if (findOpenFailure != null) {
            throw findOpenFailure;
        }
        return Optional.ofNullable(alreadyOpen);
    }

    @Override
    public OpenedPullRequest openDraft(DraftPrRequest request) {
        if (request == null) {
            throw new DraftPrException("PR 생성 요청이 없습니다");
        }
        // 🔴 실패 모드에서도 기록은 남긴다 — 「불렀는데 실패했다」와 「아예 안 불렀다」를
        //    소비자가 가를 수 있어야 한다. FR-12 검증이 그 차이에 걸려 있다
        openDraftRequests.add(request);
        if (openDraftFailure != null) {
            throw openDraftFailure;
        }
        String forkFullName = request.head().fork().fullName();
        return new OpenedPullRequest(nextPrNumber,
                "https://github.test/%s/pull/%d".formatted(request.upstream().fullName(),
                        nextPrNumber),
                request.headRef(),
                "https://github.test/" + forkFullName);
    }
}
