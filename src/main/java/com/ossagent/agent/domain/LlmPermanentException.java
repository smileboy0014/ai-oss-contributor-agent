package com.ossagent.agent.domain;

/**
 * 재전송해도 결과가 같은 실패 — 거부 · 상한 절단 · 잘못된 요청.
 *
 * <p>여기에 재시도를 걸면 <b>무한 루프</b>가 되거나 같은 돈을 반복해서 태운다.
 * 어댑터의 재시도 루프는 이 타입을 즉시 바깥으로 흘려보낸다.
 *
 * <p>{@link LlmFailureReason#TRUNCATED} 가 여기 있는 것이 의외로 보일 수 있다. 같은 요청을
 * 같은 상한으로 다시 보내면 <b>같은 지점에서 잘린다</b> — 성공 확률이 사실상 0 인데 입력
 * 토큰은 매번 전액 과금된다. 게다가 절단은 애초에 <b>전송 실패가 아니다</b>. 전송은 성공했고
 * 모델 출력이 예산을 넘었을 뿐이라, 고칠 수 있는 주체는 어댑터가 아니라 호출자다.
 * 여기서 재시도하면 S-6 이 갈라 놓은 두 축이 다시 붙는다.
 */
public class LlmPermanentException extends LlmException {

    public LlmPermanentException(LlmFailureReason reason, LlmCallSite callSite) {
        this(reason, callSite, null);
    }

    /**
     * 사용량을 아는 실패용 — 절단이 여기 해당한다.
     *
     * @param usage 응답에서 받은 실제 소비 토큰. 호출자가 상한 조정을 판단할 근거다
     */
    public LlmPermanentException(LlmFailureReason reason, LlmCallSite callSite, LlmUsage usage) {
        super(reason, callSite, usage);
        if (reason.isRetryable()) {
            throw new IllegalArgumentException(
                    "재시도 가능 사유로 permanent 예외를 만들 수 없다: " + reason);
        }
    }

    @Override
    public boolean retryable() {
        return false;
    }
}
