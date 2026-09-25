package com.ossagent.agent.domain;

/**
 * 한 번의 LLM 호출이 먹은 토큰.
 *
 * <p><b>비용이 보이지 않으면 재시도 루프가 조용히 돈을 태운다.</b> 이 값이 모든 호출에서
 * 빠짐없이 나오는 것이 이 능력의 존재 이유 중 하나다.
 *
 * @param inputTokens  프롬프트 토큰
 * @param outputTokens 생성 토큰
 */
public record LlmUsage(int inputTokens, int outputTokens) {

    public LlmUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException(
                    "토큰 수는 음수일 수 없다: input=" + inputTokens + " output=" + outputTokens);
        }
    }

    /**
     * 응답을 받지 못해 사용량을 알 수 없는 경우.
     *
     * <p>⚠️ <b>0 이 「안 썼다」는 뜻이 아니다</b> — 모르는 것이다. 타임아웃으로 끊긴 호출도
     * 모델은 이미 토큰을 생성했고 과금된다. 장부에 0 으로 남는 것과 비용이 0 인 것을
     * 같게 읽지 않는다.
     */
    public static LlmUsage unknown() {
        return new LlmUsage(0, 0);
    }
}
