package com.ossagent.candidate.domain;

/**
 * 없는 후보를 조회했다.
 *
 * <p>⚠️ <b>HTTP 매핑을 여기 달지 않는다.</b> 도메인 예외에 {@code @ResponseStatus} 를 붙이면
 * domain 이 HTTP 를 알게 되고, 같은 예외를 스케줄러·이벤트 진입점에서 던질 때 의미가 어긋난다 —
 * {@code architecture.md} 규율 ①. 404 매핑은 {@code support/web} 한 곳에서 한다.
 */
public class CandidateNotFoundException extends RuntimeException {

    public CandidateNotFoundException(Long candidateId) {
        super("후보를 찾을 수 없습니다 candidateId=" + candidateId);
    }
}
