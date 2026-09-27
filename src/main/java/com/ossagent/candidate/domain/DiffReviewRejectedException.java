package com.ossagent.candidate.domain;

/**
 * 리뷰를 수행할 수 없거나 응답을 믿을 수 없다 — 이슈 #20.
 *
 * <p>🔴 <b>{@link ReviewVerdict#UNDETERMINED} 와 다르다.</b> 저쪽은 「돌았고 응답도 받았는데
 * 판정이 없다」이고, 이쪽은 <b>「판정을 시작조차 못 했거나 응답이 스키마를 만족하지 못했다」</b>다.
 *
 * <h2>🔴 재시도 대상이 아니다 — 사유를 싣는 이유</h2>
 *
 * <p>둘 다 <b>같은 입력에 같은 결과</b>다. {@link Reason#TOO_LARGE} 는 diff 가 줄어야 풀리고,
 * {@link Reason#SCHEMA} 는 모델이 같은 프롬프트에 같은 형태로 답한다. 재시도하면
 * <b>고칠 수 없는 것에 Q-6 예산 3바퀴를 태운다.</b>
 *
 * <p>그래서 사유를 <b>값으로</b> 싣는다 — 호출자(#21)가 「재시도할 것」과 「사람에게 넘길 것」을
 * 가를 수 있어야 한다. 예외 타입만으로는 그 분기를 만들 수 없다.
 *
 * <p>⚠️ 전송 계층 실패({@code LlmException})와도 다르다 — <b>저쪽은 재시도가 의미 있다.</b>
 */
public class DiffReviewRejectedException extends RuntimeException {

    /**
     * 🔴 <b>「재시도 가능한가」를 enum 자신이 답한다.</b>
     *
     * <p>호출자(#21)가 {@code switch} 로 판정하게 두면, <b>새 사유가 추가될 때 그쪽이
     * 기본값으로 「재시도함」에 떨어진다.</b> 여기 두면 값을 더하는 사람이
     * {@link #retryable()} 를 보게 되고, 컴파일러가 강제하지는 못해도 <b>같은 파일 안에서</b>
     * 판단하게 된다.
     */
    public enum Reason {
        /**
         * diff 가 상한을 넘었다. <b>자르지 않는다</b> — 안 본 부분에 문제가 있었을 수 있다.
         *
         * <p>재시도 불가 — 같은 diff 는 매 바퀴 같은 크기다.
         */
        TOO_LARGE(false),

        /**
         * 응답이 스키마를 만족하지 못했다.
         *
         * <p>재시도 불가 — 모델은 같은 프롬프트에 대체로 같은 형태로 답한다.
         * ⚠️ 「대체로」라 완전하지는 않지만, <b>틀리는 방향이 안전하다</b> — 재시도를
         * 안 해서 잃는 것은 한 번의 기회이고, 해서 잃는 것은 Q-6 예산 전부다.
         */
        SCHEMA(false);

        private final boolean retryable;

        Reason(boolean retryable) {
            this.retryable = retryable;
        }

        /** 🔴 재시도하면 결과가 달라질 수 있는가. 지금은 전부 {@code false} 다. */
        public boolean retryable() {
            return retryable;
        }
    }

    private final transient Reason reason;

    public DiffReviewRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
