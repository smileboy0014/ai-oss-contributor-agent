package com.ossagent.candidate.domain;

import java.util.List;

/**
 * 구현 계획대로 <b>코드를 고치는</b> 능력 — #18 · PRD §14.
 *
 * <h2>계층</h2>
 *
 * <pre>
 * agent/domain/LanguageModel (1층 — #10)
 *   └ candidate/domain/CodingAgent (2층)
 *       └ candidate/adapter/out/llm/LlmCodingAgent
 * </pre>
 *
 * <p>{@link ImplementationPlanner}(#16)와 같은 자리다 — 같은 1층 능력 위에 단계별 2층이 얹힌다.
 *
 * <h2>🔴 계획 밖 파일을 만들 수 없게 한다</h2>
 *
 * <p>이슈 완료 조건은 「계획에 없는 파일을 <b>건드리면</b> 중단」이다. 구현은 두 겹으로 막는다.
 *
 * <table border="1">
 *   <caption>두 겹</caption>
 *   <tr><th>어디</th><th>무엇</th><th>성격</th></tr>
 *   <tr><td>이 능력의 구현</td><td>모델이 계획 밖 경로를 돌려주면 그 자리에서 중단</td>
 *       <td><b>조기 차단</b></td></tr>
 *   <tr><td>산출 직전 (D)</td><td>diff 의 경로 집합 ⊆ 계획의 경로 집합</td>
 *       <td>🔴 <b>게이트</b></td></tr>
 * </table>
 *
 * <p>⚠️ <b>조기 차단만으로는 부족하다.</b> 샌드박스에서 도는 포맷터가 워크스페이스를 RW 로
 * 잡고 임의 파일을 고친다 — {@code spotlessApply} 한 번이면 전 저장소가 바뀌고,
 * 그것은 모델 출력에 나타나지 않는다.
 *
 * <h2>⚠️ 출력을 신뢰하지 않는다</h2>
 *
 * <p>{@code external-deps.md} 가 「LLM 출력은 빌드·테스트로 거른다」를 규율로 둔다.
 * 이 능력의 결과는 <b>후보</b>이지 정답이 아니고, 검증은 {@code ChangeVerifier}(#19)가 한다.
 */
public interface CodingAgent {

    /**
     * 계획의 파일들을 실제 내용으로 만든다.
     *
     * @param candidateId 후보 식별자 — {@code AgentRun} 상관관계용
     * @param attempt     🔴 사이클 번호. {@code AgentRun} 불변식이 「같은 사이클의 행이 같은
     *                    {@code attempt}」(Q-6)라 <b>호출자가 넘긴다</b>
     * @param input       계획과 저장소 컨텍스트
     * @return 계획의 파일 목록에 <b>대응하는</b> 생성 결과
     * @throws CodingOutOfPlanException 모델이 계획 밖 경로를 돌려줬다 — <b>조기 차단</b>
     * @throws com.ossagent.agent.domain.LlmException 호출 자체가 실패했다
     */
    List<GeneratedFile> write(Long candidateId, int attempt, CodingInput input);
}
