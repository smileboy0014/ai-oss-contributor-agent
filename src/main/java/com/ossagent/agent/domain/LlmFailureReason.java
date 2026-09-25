package com.ossagent.agent.domain;

/**
 * LLM 호출이 실패한 이유. <b>도메인이 아는 유일한 실패 어휘</b>다.
 *
 * <p>여기에 HTTP 상태코드를 두지 않는다 — domain 이 HTTP 를 알게 되면 규율 ① 위반이고,
 * 「HTTP 매핑은 {@code support/web} 한 곳」이라는 배치 규칙과도 어긋난다.
 * 상태코드는 <b>어댑터 로그에만</b> 남는다.
 *
 * <p>SDK 예외 메시지를 그대로 옮기지 않는 이유도 같다 — 예외 본문에 요청 URL·헤더가 실려
 * 나오는 것이 가장 흔한 시크릿 유출 사고다 (S-4).
 *
 * <p>⚠️ 여기서 말하는 재시도는 <b>전송 재시도</b>(같은 요청 재전송)다.
 * 파이프라인 재시도 상한({@code agent.execution.max-retries})과 다른 축이다 — S-6.
 */
public enum LlmFailureReason {

    /** 응답이 제한 시간 안에 오지 않았다. 모델은 이미 토큰을 생성했을 수 있다 */
    TIMEOUT(true),

    /** 레이트리밋 */
    RATE_LIMITED(true),

    /** 5xx · 연결 실패 등 일시적 장애 */
    UNAVAILABLE(true),

    /** 모델이 요청을 거부했다. 재전송해도 같은 답이 온다 */
    REJECTED(false),

    /**
     * 출력 토큰 상한에서 잘렸다.
     *
     * <p><b>재전송해도 같은 지점에서 잘린다</b> — 같은 요청을 다시 보내는 것은 돈만 태운다.
     * 상한을 올릴지, 요청을 쪼갤지는 호출자의 판단이므로 전송 재시도 대상이 아니다.
     */
    TRUNCATED(false),

    /** 잘못된 요청 · 인증 실패 등 우리 쪽 오류. 재시도하면 무한 루프가 된다 */
    INVALID_REQUEST(false);

    private final boolean retryable;

    LlmFailureReason(boolean retryable) {
        this.retryable = retryable;
    }

    /** 같은 요청을 그대로 재전송할 가치가 있는가. */
    public boolean isRetryable() {
        return retryable;
    }
}
