package com.ossagent.candidate.domain;

/**
 * LLM 이 내놓은 코딩 산출물이 <b>쓸 수 있는 모양이 아니다</b> — #18.
 *
 * <p>{@link PlanRejectedException}(#16)과 같은 결이다. 축이 셋인 것도 같다.
 *
 * <table border="1">
 *   <caption>어느 예외인가</caption>
 *   <tr><th>무엇</th><th>어느 예외</th></tr>
 *   <tr><td>호출 자체가 실패 (타임아웃·5xx·절단)</td><td>{@code LlmException} 계열</td></tr>
 *   <tr><td><b>스키마</b>가 틀렸다 (JSON 아님·{@code files} 없음)</td><td>🔴 <b>이것</b></td></tr>
 *   <tr><td><b>계획 밖 경로</b>를 돌려줬다</td><td>{@link CodingOutOfPlanException}</td></tr>
 * </table>
 *
 * <h2>🔴 왜 계획 밖 경로를 따로 두나</h2>
 *
 * <p>둘의 <b>재시도 판정이 다르다.</b> 스키마 위반은 모델이 한 번 실수한 것이라
 * 재생성할 가치가 있지만, <b>계획 밖 경로는 같은 계획·같은 프롬프트로 다시 부르면
 * 같은 결과가 나올 가능성이 높다.</b>
 *
 * <p>⚠️ 하나로 합치면 고칠 수 없는 것에 재시도 예산(Q-6 의 3바퀴)을 태우고,
 * 후보가 <b>코드 문제 없이</b> {@code FAILED} 로 떨어진다.
 *
 * <p>⚠️ 메시지에 <b>응답 본문을 넣지 않는다</b> — 대상 저장소 텍스트가 섞여 있다 (S-4).
 */
public class CodingRejectedException extends RuntimeException {

    public CodingRejectedException(String message) {
        super(message);
    }
}
