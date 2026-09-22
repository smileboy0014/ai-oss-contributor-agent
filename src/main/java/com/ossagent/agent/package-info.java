/**
 * LLM 오케스트레이션과 샌드박스 실행 — <b>능력과 어댑터</b>.
 *
 * <p><b>소유</b> — 프롬프트 조립, LLM 호출, 샌드박스 컨테이너 수명, 재시도·타임아웃 정책.
 * 능력 인터페이스는 이름으로 선언하고({@code CodeSandbox} · {@code CodingAgent} ·
 * {@code DiffReviewer}) 구현은 {@code adapter/out} 에 기술 이름으로 둔다.
 *
 * <p><b>소유하지 않음</b> — <b>실행 기록 엔티티</b>. {@code AgentRun}·{@code GeneratedChange} 는
 * {@code candidate} 애그리거트에 속한다. 「에이전트가 만들어낸다」는 사실이 소유를 정하지 않는다.
 * 후보의 상태 전이와 <b>같은 트랜잭션에서 일관성을 지켜야 하는 것</b>이 소유를 정한다 —
 * 재시도 상한 소진 판정이 {@code candidate.status} 와 {@code agentRun.attempt} 를 함께 본다(불변식 ⑧).
 *
 * <p>대상 저장소의 빌드·테스트는 <b>반드시</b> 샌드박스 능력을 통해서만 실행한다.
 * 호스트에서 직접 실행하는 경로를 만들지 않는다 —
 * {@code .claude/rules/context/safety-boundaries.md} S-3.
 *
 * <p>아직 비어 있다.
 */
package com.ossagent.agent;
