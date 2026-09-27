package com.ossagent.pullrequest.domain;

/**
 * Draft PR 을 만들지 못했다.
 *
 * <h2>🔴 {@link UpstreamWriteAttemptException} 과 섞지 않는다</h2>
 *
 * <p>저쪽은 <b>S-1 위반 시도</b>다 — 「Fork 가 아닌 곳에 쓰려 했다」. 이쪽은 정당한 대상에
 * 쓰려다 <b>실패한 것</b>이다. 한 타입으로 묶으면 로그·알림에서 「경계를 넘으려 했다」와
 * 「GitHub 이 422 를 줬다」가 같은 심각도로 읽힌다.
 *
 * <p>⚠️ 이 예외가 던져진 시점에 <b>후보는 전이하지 않은 상태</b>여야 한다 — 대외 호출이
 * 성공한 뒤에만 쓰기 트랜잭션을 여는 순서가 그것을 보장한다.
 */
public class DraftPrException extends RuntimeException {

    public DraftPrException(String message) {
        super(message);
    }

    public DraftPrException(String message, Throwable cause) {
        super(message, cause);
    }
}
