package com.ossagent.agent.domain;

/**
 * LLM 을 부르는 능력. PRD §6.1 의 <b>4개 호출 지점이 공유하는 유일한 통로</b>다.
 *
 * <p>분석·계획·코딩·리뷰가 각자 SDK 를 부르면 타임아웃·재시도·토큰 기록이 네 벌로 갈라지고,
 * 갈라지는 순간 비용이 보이지 않게 된다. 그래서 통로를 하나로 둔다.
 *
 * <p>이 인터페이스 위에 도메인 능력({@code IssueAnalyst} · {@code ImplementationPlanner} ·
 * {@code CodingAgent} · {@code DiffReviewer})이 <b>2층으로</b> 얹힌다. 그쪽은 소비자 이슈의 몫이다.
 *
 * <h2>구현이 반드시 지키는 것</h2>
 * <ol>
 *   <li><b>송신 직전 {@link PromptScrubber} 를 통과시킨다</b> — S-4. 우회 인자를 두지 않는다.
 *       프롬프트는 대상 저장소 파일을 싣고 나가고, 유출 상대가 <b>모델 제공자</b>다</li>
 *   <li><b>{@code ctx} 를 받은 호출은 빠짐없이 실행 이력에 남는다</b> —
 *       {@code RecordingLanguageModel} 데코레이터가 강제한다. 실패한 호출도 토큰을 먹으므로
 *       실패도 남긴다</li>
 *   <li><b>상한 절단·거부는 성공이 아니다</b> — {@link LlmPermanentException} 으로 나간다.
 *       잘린 응답을 {@link LlmResponse} 로 돌려주면 소비자가 잘린 JSON 을 진실로 파싱한다</li>
 *   <li><b>트랜잭션 안에서 부르지 않는다</b> — 대외 호출이다. 커넥션을 붙잡는다</li>
 * </ol>
 *
 * <p>구현체는 {@code adapter/out/llm} 에 <b>기술 이름</b>으로 둔다 —
 * {@code AnthropicLanguageModel}. 규율 ③.
 */
public interface LanguageModel {

    /**
     * 한 번 호출하고 응답을 받는다.
     *
     * @param ctx 실행 이력 좌표. <b>선택이 아니다</b> — 이것이 없으면 기록을 잊을 수 있고,
     *            그러면 「호출마다 토큰 기록」이 규약일 뿐 구조가 아니게 된다
     * @param request 보낼 프롬프트. 구현이 스크럽한 뒤 송신한다
     * @return 모델이 말한 것. <b>진실이 아니다</b>
     * @throws LlmTransientException 재전송할 가치가 있는 실패
     * @throws LlmPermanentException 재전송해도 같은 실패 (거부 · 상한 절단 · 잘못된 요청)
     */
    LlmResponse complete(AgentRunContext ctx, LlmRequest request);
}
