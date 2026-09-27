/**
 * 사용자 Fork 로의 push 와 Draft PR 생성 — <b>능력과 어댑터</b>.
 *
 * <p><b>소유</b> — Fork 확보, 브랜치 생성, commit·push, Draft PR 생성 호출.
 * 능력 인터페이스는 <b>능력 이름</b>으로 선언하고 구현은 {@code adapter/out/github} 에 둔다.
 *
 * <table border="1">
 *   <caption>능력</caption>
 *   <tr><th>능력</th><th>구현</th><th>상태</th></tr>
 *   <tr><td>{@link com.ossagent.pullrequest.domain.ForkPublisher}</td>
 *       <td>{@code GitHubForkPublisher}</td>
 *       <td>✅ #22 — Fork 확보 · 동기화 · commit · push · 브랜치 삭제</td></tr>
 *   <tr><td>{@code DraftPrPublisher}</td><td>—</td><td>⬜ #23</td></tr>
 * </table>
 *
 * <p>⚠️ 초안은 첫 능력의 이름을 {@code ForkRegistry} 로 예시했으나 실제로는
 * {@code ForkPublisher} 다 — 하는 일이 「확보」에 그치지 않고 <b>올리는 것까지</b>이기 때문이다.
 *
 * <p>🔴 <b>둘을 합치지 않는다.</b> 합치면 「브랜치를 올리고 싶어 부른 호출이 PR 생성을
 * 부산물로」 쥐게 되고, 그 순간 S-6 의 <b>세 번째 승인 게이트</b>
 * ({@code POST /api/candidates/{id}/pull-request})가 사라진다.
 *
 * <p><b>소유하지 않음</b> — <b>{@code PullRequest} 엔티티</b>. 그것은 {@code candidate}
 * 애그리거트에 속한다. {@code UNIQUE(candidate_id)} 와 「PR 은 항상 draft」(불변식 ①③⑨)가
 * 후보 상태와 <b>함께 서야 하는</b> 불변식이기 때문이다.
 *
 * <p>쓰기 대상은 <b>사용자 Fork 뿐</b>이고, 생성하는 PR 은 <b>항상 draft</b> 다.
 * 원본 저장소 push·머지 경로를 만들지 않는다 —
 * {@code .claude/rules/context/safety-boundaries.md} S-1 · S-2.
 *
 * <p>🔴 <b>{@code ForkPublisher} 에 호출자가 없다 — 의도다.</b> 배선은 #23 이 세 번째 승인
 * 게이트 뒤에 놓는다. 지금 스케줄러나 {@code implement} 경로에서 부르면 그 시점에 S-6 위반이다.
 */
package com.ossagent.pullrequest;
