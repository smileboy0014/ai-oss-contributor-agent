package com.ossagent.agent.domain;

/**
 * LLM 이 돌려준 것. <b>진실이 아니라 「모델이 이렇게 말했다」는 사실</b>일 뿐이다.
 *
 * <p>⚠️ <b>파싱 편의 메서드를 두지 않는다.</b> {@code asJson()} · {@code parseAs(Class)} 같은 것을
 * 하나라도 만들면 그 순간 「모델 응답을 그대로 진실로 쓰는 경로」가 생긴다 —
 * {@code external-deps.md} 가 금지한 바로 그것이다. 파싱은 각 소비자가 자기 스키마로 하고,
 * 실패는 예외로 드러나야 한다.
 *
 * <p>이 제품의 품질 축은 「좋은 코드를 쓰는가」가 아니라 <b>「나쁜 결과를 걸러내는가」</b>다.
 * 편의 메서드 하나가 게이트를 느슨하게 만들면 그것은 기능 추가가 아니라 제품 훼손이다.
 *
 * <p>상한에서 잘린 응답·거부된 응답은 <b>이 타입으로 돌아오지 않는다.</b> 예외다 —
 * 잘린 JSON 을 소비자가 파싱하는 것을 구조적으로 막는다.
 *
 * @param text  모델이 생성한 텍스트. 외부에서 온 텍스트이므로 적재할 때 스크럽 대상이다
 * @param usage 이 호출이 먹은 토큰
 */
public record LlmResponse(String text, LlmUsage usage) {

    public LlmResponse {
        if (text == null) {
            throw new IllegalArgumentException("text 는 null 일 수 없다 — 빈 응답은 빈 문자열이다");
        }
        if (usage == null) {
            throw new IllegalArgumentException("usage 는 필수다 — 비용이 보이지 않으면 안 된다");
        }
    }
}
