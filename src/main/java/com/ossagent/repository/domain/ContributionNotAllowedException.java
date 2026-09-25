package com.ossagent.repository.domain;

/**
 * 이 저장소에는 기여할 수 없다 — S-5 게이트가 막았다.
 *
 * <p>🔴 <b>예외인 것이 설계다.</b> {@code boolean} 을 돌려주면 호출자가 무시할 수 있고,
 * 무시된 게이트는 게이트가 아니다. S-1 의 push 직전 어설션과 같은 모양이다 —
 * 「확인했는가」가 아니라 「통과하지 못하면 진행이 멈춘다」여야 한다.
 *
 * <p>막는 경우는 셋이고, <b>셋 다 통과시키면 안 된다.</b>
 *
 * <table border="1">
 *   <caption>차단 사유</caption>
 *   <tr><th>{@link Reason}</th><th>뜻</th></tr>
 *   <tr><td>{@code NOT_ANALYZED}</td><td>규약을 아직 읽지 않았다. 「RepositoryPolicy 없이 구현
 *       단계로 넘어가지 않는다」(S-5)</td></tr>
 *   <tr><td>{@code UNDETERMINED}</td><td>보류 — 판정이 서지 않았다. <b>「판정 불가를 통과로
 *       처리」가 정확히 S-5 위반</b>이다</td></tr>
 *   <tr><td>{@code FORBIDDEN}</td><td>AI 기여를 금지하는 저장소다</td></tr>
 * </table>
 */
public class ContributionNotAllowedException extends RuntimeException {

    public enum Reason {
        NOT_ANALYZED,
        UNDETERMINED,
        FORBIDDEN
    }

    private final transient Reason reason;

    public ContributionNotAllowedException(Long repositoryId, Reason reason) {
        super("기여할 수 없는 저장소다: repositoryId=%d reason=%s".formatted(repositoryId, reason));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
