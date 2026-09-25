package com.ossagent.candidate.domain;

/**
 * 허용되지 않은 상태 전이. <b>조용히 무시하지 않고 던진다</b> — S-6.
 *
 * <p>{@link IllegalStateException} 을 상속한다. {@code testing-philosophy.md} 의 예시가
 * {@code assertThatThrownBy(...).isInstanceOf(IllegalStateException.class)} 로 적혀 있어
 * 규약 문서와 어긋나지 않는다.
 *
 * <p>⚠️ <b>HTTP 매핑은 여기 달지 않는다.</b> 도메인 예외에 {@code @ResponseStatus} 를 붙이면
 * domain 이 HTTP 를 알게 되고, 같은 예외를 스케줄러가 던질 때 의미가 없어진다 —
 * {@code architecture.md} §4. 불법 전이는 409 가 맞고, 매핑은 {@code support/web} 한 곳에서
 * 엔드포인트와 함께 붙인다(#24).
 *
 * <p>메시지에는 <b>상태 이름과 식별자만</b> 담는다. {@code analysis}(LLM 응답)·{@code diff} 를
 * 싣지 않는다 — S-4.
 */
public class CandidateTransitionException extends IllegalStateException {

    public CandidateTransitionException(String message) {
        super(message);
    }

    static CandidateTransitionException illegal(Long candidateId, CandidateStatus from,
            CandidateStatus to) {
        return new CandidateTransitionException(
                "허용되지 않은 상태 전이입니다 candidateId=" + candidateId
                        + " " + from + " → " + to
                        + " (허용: " + from.allowedNext() + ")");
    }
}
