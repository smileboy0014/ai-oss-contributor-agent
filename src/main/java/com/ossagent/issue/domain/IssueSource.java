package com.ossagent.issue.domain;

/**
 * 대상 저장소의 open 이슈를 읽어 오는 <b>능력</b>.
 *
 * <p>기술 이름({@code GitHub})은 구현체에만 나타난다 — 규율 ③.
 * 구현은 트랜잭션 밖에서 호출한다 — 대외 호출 지연이 DB 커넥션 점유로 번진다.
 *
 * <p>🔴 읽기만 있다. 이슈에 코멘트를 남기는 경로는 <b>만들지 않는다</b> —
 * 대상 저장소에 글을 남기는 모든 경로는 사람 승인 없이 쓰지 않는다({@code safety-boundaries.md} S-2).
 */
public interface IssueSource {

    /**
     * open 이슈 한 페이지를 읽는다.
     *
     * <p>결과에는 <b>Pull Request 가 섞여 있을 수 있다.</b> GitHub 의 이슈 목록 API 가 PR 도
     * 돌려주기 때문이다. 걸러내려면 {@link IssuePage#issuesOnly()} 를 쓴다.
     *
     * <p>조회 실패는 런타임 예외로 전파된다. 레이트리밋을 만나면 실패가 아니라 <b>지연</b>으로
     * 다뤄야 하는데, 얼마나 미룰지는 호출자가 정한다(이슈 #8).
     */
    IssuePage fetchOpenIssues(IssueQuery query);
}
