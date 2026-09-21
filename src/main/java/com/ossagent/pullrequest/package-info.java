/**
 * 사용자 Fork 로의 push 와 Draft PR 생성.
 *
 * <p><b>소유</b> — Fork·브랜치·PR 메타데이터, PR 템플릿 적용 결과.
 * <p><b>소유하지 않음</b> — 코드 생성·검증({@code agent}).
 *
 * <p>쓰기 대상은 <b>사용자 Fork 뿐</b>이고, 생성하는 PR 은 <b>항상 draft</b> 다.
 * 원본 저장소 push·머지 경로를 만들지 않는다 —
 * {@code .claude/rules/context/safety-boundaries.md} S-1 · S-2.
 *
 * <p>아직 비어 있다.
 */
package com.ossagent.pullrequest;
