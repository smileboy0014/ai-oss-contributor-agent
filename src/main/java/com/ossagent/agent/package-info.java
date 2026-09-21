/**
 * LLM 오케스트레이션 — 이슈 분석 · 구현 계획 · 코드 생성 · AI 리뷰, 그리고 샌드박스 실행.
 *
 * <p><b>소유</b> — 실행 이력({@code AgentRun}), 생성 변경분({@code GeneratedChange}),
 * 재시도 카운터, 토큰·비용 집계.
 * <p><b>소유하지 않음</b> — 후보 상태 전이({@code candidate}) · PR 생성({@code pullrequest}).
 *
 * <p>대상 저장소의 빌드·테스트는 <b>반드시</b> 샌드박스 능력 인터페이스를 통해서만 실행한다.
 * 호스트에서 직접 실행하는 경로를 만들지 않는다 —
 * {@code .claude/rules/context/safety-boundaries.md} S-3.
 *
 * <p>아직 비어 있다.
 */
package com.ossagent.agent;
