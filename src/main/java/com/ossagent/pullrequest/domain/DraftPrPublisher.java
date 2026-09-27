package com.ossagent.pullrequest.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;
import java.util.Optional;

/**
 * 검증을 통과한 브랜치로 upstream 에 <b>Draft PR</b> 을 여는 능력 — S-2.
 *
 * <pre>
 * Fork 브랜치 ──(사람의 승인)──▶ Draft PR ──▶ 사람이 제출
 *  (#22 가 만든다)              여기서 자동화가 끝난다
 * </pre>
 *
 * <h2>🔴 이 능력에는 세 번째 승인 게이트가 선행한다 — S-6</h2>
 *
 * <p>{@code POST /api/candidates/{id}/pull-request} 뒤에서만 불린다. 스케줄러·
 * {@code implement} 경로에서 부르면 그 시점에 S-6 위반이고,
 * {@code ApprovalGateArchitectureTest} 가 「{@code CreateDraftPrUseCase} 를 부르는 것은
 * web 어댑터뿐」을 <b>허용목록</b>으로 고정한다.
 *
 * <h2>🔴 없는 메서드가 방어다</h2>
 *
 * <p>{@code markReadyForReview}·{@code requestReviewers}·{@code merge} 는 <b>존재 자체가
 * 반려</b>다(S-2). 「나중에 필요할지 모르니 만들어 두자」가 정확히 조항이 막는 것이다 —
 * 있으면 언젠가 불린다.
 *
 * <p>대상 저장소에 <b>글을 남기는 다른 경로</b>(이슈 코멘트·리뷰 코멘트)도 같은 취급이다.
 * 이 인터페이스가 대상 저장소에 쓰는 것은 <b>Draft PR 하나뿐</b>이다.
 */
public interface DraftPrPublisher {

    /**
     * 이 head 로 <b>이미 열려 있는</b> PR 이 있는가.
     *
     * <p>🔴 재시도·중복 호출에서 <b>두 번째 PR 을 만들지 않기 위해</b> 먼저 본다 — S-2.
     * 남의 저장소에 중복 PR 을 여는 것은 스팸으로 취급된다.
     *
     * <p>⚠️ <b>이것을 멱등성의 전부로 세지 않는다.</b> 조회와 생성 사이에 창이 있고,
     * 그 창은 후보 상태 전이({@code READY_FOR_PR → PR_CREATED}, 낙관적 잠금)와
     * {@code UNIQUE(candidate_id)} 가 함께 좁힌다. 셋이 같이 서야 한다.
     *
     * @param upstream 원본 좌표. <b>읽기</b>다
     * @param head     Fork 에 올라간 브랜치
     * @return 열려 있는 PR. 없으면 {@link Optional#empty()}
     */
    Optional<OpenedPullRequest> findOpen(RepositoryCoordinates upstream, PublishedBranch head);

    /**
     * Draft PR 을 연다.
     *
     * <p>🔴 <b>draft 가 아닌 PR 을 만들 방법이 없다.</b> {@link DraftPrRequest} 에 자리가 없고,
     * 전송 payload 가 리터럴 {@code true} 를 싣는다. 응답이 draft 가 아니면 <b>값으로 바꾸지
     * 않고 던진다</b> — 우리가 보낸 것과 GitHub 이 만든 것이 다르면 그것은 사고다.
     *
     * <p>🔴 <b>상태 전이도 영속화도 하지 않는다.</b> {@code ForkPublisher} 가 PR 을 만들지
     * 않은 것과 같은 이유다 — 능력을 합치면 게이트가 부산물이 된다.
     *
     * @throws DraftPrException            GitHub 이 거부했다 · 응답이 draft 가 아니다
     * @throws UpstreamWriteAttemptException head 가 Fork 가 아니다 — S-1
     */
    OpenedPullRequest openDraft(DraftPrRequest request);
}
