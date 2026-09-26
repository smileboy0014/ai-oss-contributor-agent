package com.ossagent.candidate.domain;

/**
 * LLM 이 내놓은 계획이 <b>쓸 수 있는 모양이 아니다</b> — 이슈 #16.
 *
 * <p>{@code AnalysisRejectedException}(#11)과 같은 결이다. 구분해 둘 것 —
 *
 * <table>
 *   <tr><th>무엇</th><th>어느 예외</th></tr>
 *   <tr><td>호출 자체가 실패 (타임아웃·5xx·절단)</td><td>{@code LlmException} 계열</td></tr>
 *   <tr><td><b>스키마</b>가 틀렸다 (필드 누락·모르는 값)</td><td>🔴 <b>이것</b></td></tr>
 *   <tr><td>스키마는 맞는데 <b>내용</b>이 틀렸다 (없는 파일 지목)</td><td>{@code PlanVerdict} — 예외가 아니라 <b>값</b>이다</td></tr>
 * </table>
 *
 * <p>마지막 줄이 이 이슈의 설계다. <b>「모델이 틀렸다」는 정상 상황</b>이고, 그것을 값으로
 * 받아야 <b>위반 사유를 프롬프트에 되먹여 재생성</b>할 수 있다. 예외로 만들면 사유가
 * 스택트레이스로 흩어지고 재생성이 같은 실수를 반복한다.
 *
 * <p>이 예외도 <b>재생성 대상</b>이다({@code agent.plan.max-attempts}) — 모델이 JSON 을
 * 한 번 잘못 냈다고 후보를 종단으로 보내지 않는다.
 */
public class PlanRejectedException extends RuntimeException {

    public PlanRejectedException(String message) {
        super(message);
    }

    public PlanRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
