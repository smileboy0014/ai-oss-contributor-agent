/**
 * 사용자 Fork 로의 push 와 Draft PR 생성 — <b>능력과 어댑터</b>.
 *
 * <p><b>소유</b> — Fork 확보, 브랜치 생성, commit·push, Draft PR 생성 호출.
 * 능력 인터페이스는 {@code ForkRegistry} · {@code DraftPrPublisher} 같은 이름으로 선언하고
 * 구현은 {@code adapter/out/github} 에 둔다.
 *
 * <p><b>소유하지 않음</b> — <b>{@code PullRequest} 엔티티</b>. 그것은 {@code candidate}
 * 애그리거트에 속한다. {@code UNIQUE(candidate_id)} 와 「PR 은 항상 draft」(불변식 ①③⑨)가
 * 후보 상태와 <b>함께 서야 하는</b> 불변식이기 때문이다.
 *
 * <p>쓰기 대상은 <b>사용자 Fork 뿐</b>이고, 생성하는 PR 은 <b>항상 draft</b> 다.
 * 원본 저장소 push·머지 경로를 만들지 않는다 —
 * {@code .claude/rules/context/safety-boundaries.md} S-1 · S-2.
 *
 * <p>아직 비어 있다.
 */
package com.ossagent.pullrequest;
