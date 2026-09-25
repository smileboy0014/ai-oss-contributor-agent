package com.ossagent.agent.domain;

/**
 * LLM 호출 실패. <b>SDK 예외를 여기로 번역하고, 원문을 들고 다니지 않는다</b> — S-4.
 *
 * <p>SDK·HTTP 예외 본문에는 요청 URL 과 헤더가 담긴다. 그것이 로그나
 * {@code AgentRun.errorMessage} 로 흘러가는 것이 이 프로젝트에서 가장 현실적인 시크릿 유출
 * 경로다. 그래서 메시지는 <b>우리가 만든 문자열</b>뿐이고, 원인 예외를 {@code cause} 로도
 * 달지 않는다 — 스택트레이스를 찍는 순간 같은 문제가 생긴다.
 *
 * <p>진단에 필요한 상태코드·엔드포인트는 <b>어댑터가 로그로</b> 남긴다. 그쪽은 마스킹을
 * 통제할 수 있는 자리다.
 *
 * <p>재시도 가능 여부는 {@link LlmTransientException} / {@link LlmPermanentException} 두 갈래로
 * <b>타입에서</b> 갈린다. catch 하는 쪽이 {@code reason} 을 해석해야 한다면 언젠가 틀린다.
 */
public abstract class LlmException extends RuntimeException {

    private final transient LlmFailureReason reason;
    private final transient LlmCallSite callSite;
    private final transient LlmUsage usage;

    protected LlmException(LlmFailureReason reason, LlmCallSite callSite, LlmUsage usage) {
        super("LLM 호출 실패 reason=" + reason + " callSite=" + callSite);
        this.reason = reason;
        this.callSite = callSite;
        this.usage = usage;
    }

    public LlmFailureReason reason() {
        return reason;
    }

    public LlmCallSite callSite() {
        return callSite;
    }

    /**
     * 실패한 호출이 먹은 토큰 — <b>알 수 있을 때만</b> 담긴다.
     *
     * <p>절단({@link LlmFailureReason#TRUNCATED})은 응답을 받았으므로 사용량을 안다.
     * 호출자가 <b>상한을 얼마나 올려야 하는지</b> 판단할 유일한 근거이므로 반드시 싣는다.
     *
     * <p>타임아웃·연결 실패는 응답 자체가 없어 비어 있다. ⚠️ <b>비어 있다고 비용이 0 인 것은
     * 아니다</b> — 모델은 이미 생성했고 과금된다. 그 비용은 로그로만 남는다.
     */
    public java.util.Optional<LlmUsage> usage() {
        return java.util.Optional.ofNullable(usage);
    }

    /** 같은 요청을 그대로 재전송할 가치가 있는가. <b>전송 축</b>의 판단이다 — S-6. */
    public abstract boolean retryable();
}
