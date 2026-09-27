package com.ossagent.pullrequest.domain;

/**
 * Fork 에 올리지 못했다 — <b>전송 실패가 아닌</b> 실패.
 *
 * <p>전송 계층 실패(5xx · 타임아웃 · 레이트리밋)는 {@code GitHubApiException} 계열로 그대로
 * 올라간다. 그쪽은 재시도·지연 정책이 붙는 자리이고, 이 예외는 <b>다시 걸어도 같은</b> 실패다.
 *
 * <table border="1">
 *   <caption>여기로 오는 것</caption>
 *   <tr><td>Fork 가 준비되지 않았다</td><td>상한 안에 저장소가 나타나지 않았다</td></tr>
 *   <tr><td>같은 이름의 저장소가 fork 가 아니다</td>
 *       <td>🔴 owner 어설션이 <b>원리적으로 못 잡는</b> 오염 경로다 — 사람이 정리해야 한다</td></tr>
 *   <tr><td>브랜치가 이미 있는데 덮어쓰기를 허락받지 않았다</td>
 *       <td>조용히 force 로 덮지 않는다</td></tr>
 * </table>
 *
 * <p>⚠️ {@link UpstreamWriteAttemptException} 과 섞지 않는다. 그쪽은 <b>안전 경계 위반</b>이고
 * 이쪽은 <b>작업 실패</b>다. 한 타입으로 합치면 「S-1 이 발화했다」가 평범한 실패 로그에 묻힌다.
 */
public class ForkPublishException extends RuntimeException {

    public ForkPublishException(String message) {
        super(message);
    }

    public ForkPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
