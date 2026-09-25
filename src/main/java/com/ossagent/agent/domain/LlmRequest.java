package com.ossagent.agent.domain;

/**
 * LLM 에 보낼 요청. <b>SDK 타입이 도메인으로 새어 들어오지 못하게 막는 벽</b>이다.
 *
 * <p>모델 이름·온도·타임아웃 같은 <b>기술 파라미터를 여기 두지 않는다.</b> 그것은 어댑터 설정
 * ({@code agent.llm.*})이고, 도메인이 알면 모델 제공자를 바꿀 때 도메인이 흔들린다.
 *
 * <p>⚠️ {@code system} 과 {@code userPrompt} 는 <b>스크럽 대상</b>이다. 대상 저장소 파일 내용이
 * 여기 실려 나가고, 그 저장소가 시크릿을 커밋해 뒀을 수 있다. 송신 직전
 * {@link PromptScrubber} 를 반드시 통과한다 — S-4.
 *
 * @param system          시스템 프롬프트. 없으면 {@code null}
 * @param userPrompt      사용자 프롬프트
 * @param maxOutputTokens 이 호출의 출력 상한. 상한에서 잘리면 <b>성공이 아니다</b>
 *                        ({@link LlmFailureReason#TRUNCATED})
 */
public record LlmRequest(String system, String userPrompt, int maxOutputTokens) {

    public LlmRequest {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt 는 비어 있을 수 없다");
        }
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens 는 1 이상이어야 한다: " + maxOutputTokens);
        }
    }

    public boolean hasSystem() {
        return system != null && !system.isBlank();
    }

    /** 스크럽된 본문으로 교체한 사본. 원본을 변경하지 않는다. */
    public LlmRequest withScrubbed(String scrubbedSystem, String scrubbedUserPrompt) {
        return new LlmRequest(scrubbedSystem, scrubbedUserPrompt, maxOutputTokens);
    }
}
