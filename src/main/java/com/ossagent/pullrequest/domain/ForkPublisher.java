package com.ossagent.pullrequest.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;

/**
 * 검증을 통과한 변경분을 <b>사용자 Fork 에</b> 올리는 능력 — S-1.
 *
 * <pre>
 * 원본 저장소 ──fork──▶ 사용자 Fork ──▶ 브랜치 ──▶ commit ──▶ push
 *    (읽기만)              (유일한 쓰기 대상)
 * </pre>
 *
 * <p>능력 이름으로 domain 에 선언하고 구현은 기술 이름으로 {@code adapter/out/github} 에 둔다 —
 * {@code architecture.md} 규율 ③.
 *
 * <h2>🔴 여기서 PR 을 만들지 않는다 — S-2 · S-6</h2>
 *
 * <p>Draft PR 생성은 {@code DraftPrPublisher}(#23)의 일이고, 그 앞에 <b>세 번째 승인 게이트</b>
 * ({@code POST /api/candidates/{id}/pull-request})가 있어야 한다. 두 능력을 합치면
 * 「브랜치를 올리고 싶어 부른 호출이 PR 생성을 부산물로」 쥐게 되고, 그 순간 게이트가 사라진다 —
 * #16 이 {@code PolicyClearance} 와 {@code ContributionConstraints} 를 가른 것과 같은 논리다.
 *
 * <h2>🔴 이 인터페이스에 호출자가 없다 — 의도다</h2>
 *
 * <p>배선은 #23 이 승인 게이트 <b>뒤에</b> 놓는다. 지금 스케줄러나 {@code implement} 경로에서
 * 부르게 하면 그 시점에 S-6 위반이다. #16 의 {@code PlanImplementationUseCase.build()} 와 같은 처리다.
 */
public interface ForkPublisher {

    /**
     * Fork 를 확보한다 — 없으면 만들고, 있으면 <b>그것이 정말 이 upstream 의 fork 인지 확인한 뒤</b>
     * 재사용한다.
     *
     * <p>🔴 <b>존재 여부만 보면 안 된다.</b> 사용자가 같은 이름의 무관한 저장소를 이미 갖고 있으면
     * 그것을 Fork 로 오인해 거기에 commit 을 민다 — <b>owner 어설션은 통과한다</b>(owner 가 실제로
     * 우리이기 때문이다). 어설션이 원리적으로 못 잡는 경로라 여기서 막아야 한다.
     *
     * @param upstream 원본 좌표. <b>읽기만</b> 한다 (fork 생성 요청 하나가 유일한 예외)
     * @throws ForkPublishException 준비 상한 초과 · 같은 이름의 저장소가 fork 가 아니다
     */
    ForkRef ensureFork(RepositoryCoordinates upstream);

    /**
     * Fork 의 기준 브랜치를 upstream 과 맞춘다.
     *
     * <p>🔴 실패를 <b>예외가 아니라 값</b>으로 돌려준다 — 「치명적이지 않아서」가 아니라
     * <b>진행 판단의 주체가 PR 을 만드는 쪽</b>이기 때문이다. 이 능력은 PR 을 만들지 않는다.
     *
     * <p>돌려주는 {@link SyncedFork} 는 {@link PublishRequest} 가 <b>인자로 요구</b>하므로,
     * 결과를 보지 않고 {@link #publish} 로 넘어가는 경로가 없다.
     */
    SyncedFork syncWithUpstream(ForkRef fork, BaseBranch baseBranch);

    /**
     * 커밋을 만들고 브랜치를 가리키게 한다.
     *
     * <p>🔴 쓰기 직전 owner 어설션이 여기서 발화한다.
     *
     * @throws UpstreamWriteAttemptException 쓰기 대상이 Fork 가 아니다 — <b>S-1</b>
     * @throws ForkPublishException          브랜치가 이미 있는데 {@code allowUpdate} 가 아니다
     */
    PublishedBranch publish(PublishRequest request);

    /**
     * Fork 안의 브랜치를 지운다.
     *
     * <p>🔴 {@link ForkRef} 를 요구하므로 <b>upstream 을 지목할 수 없다.</b>
     * 브랜치 삭제·force 갱신도 Fork 안에서만 일어난다 — S-1.
     */
    void deleteBranch(ForkRef fork, BranchName branchName);
}
