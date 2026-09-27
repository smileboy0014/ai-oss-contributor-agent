package com.ossagent.pullrequest.domain;

/**
 * 쓰기 대상이 사용자 Fork 가 아니다 — <b>S-1</b>.
 *
 * <h2>이 예외가 던져졌다는 것은 이미 사고 직전이라는 뜻이다</h2>
 *
 * <p>정상 흐름에서는 발생하지 않는다. 발생했다면 <b>원본(upstream) 저장소에 쓰려는 코드
 * 경로가 실재한다</b>는 뜻이고, 그것은 기능 결함이 아니라 <b>안전 경계 위반</b>이다.
 * 잡아서 넘기지 않는다 — 재시도 대상도 아니다.
 *
 * <p>🔴 <b>`RuntimeException` 인 것이 의도다.</b> checked 로 두면 호출부가
 * {@code catch (…) { /* ignore *&#47; }} 로 삼킬 자리가 생긴다. 이 예외는
 * <b>흐름을 끊는 것이 유일한 올바른 처리</b>다.
 *
 * <p>⚠️ 메시지에 <b>우회법을 적지 않는다</b> — {@code safety-boundaries.md} 의
 * 「게이트의 거부 메시지가 우회법을 가르치지 않는다」. 「설정을 바꾸면 통과한다」류의
 * 안내는 여기서 참이 아니고(위험이 사라지지 않는다), 적으면 다음 사람이 그것을 시도한다.
 * 적는 것은 <b>무엇이 어긋났는지</b>뿐이다.
 */
public class UpstreamWriteAttemptException extends RuntimeException {

    public UpstreamWriteAttemptException(String message) {
        super(message);
    }
}
