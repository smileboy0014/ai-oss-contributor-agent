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
 * <h2>LLM 능력은 2층이다</h2>
 *
 * <p><b>1층 — 전송</b>: {@code LanguageModel} 하나. PRD §6.1 의 4개 호출 지점이 공유한다.
 * 타임아웃·전송 재시도·토큰 기록·프롬프트 스크럽이 전부 여기 모인다. 갈라지면 비용이
 * 보이지 않게 된다.
 *
 * <p><b>2층 — 도메인 능력</b>: {@code IssueAnalyst} · {@code ImplementationPlanner} ·
 * {@code CodingAgent} · {@code DiffReviewer}. 1층 위에 얹히고 <b>아직 없다</b> — 소비자 이슈의 몫이다.
 *
 * <p>🔴 노출되는 {@code LanguageModel} 빈은 {@code RecordingLanguageModel} <b>하나뿐</b>이다.
 * 속 구현을 빈으로 내보내지 않으므로 실행 이력을 건너뛰고 LLM 을 부를 경로가 존재하지 않는다.
 *
 * <p>샌드박스({@code CodeSandbox})는 아직 없다 — #17.
 */
package com.ossagent.agent;
