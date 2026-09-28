package com.ossagent.candidate.domain;

/**
 * 착수를 지금 받을 수 없다 — 이미 돌고 있거나, 같은 저장소를 다른 후보가 쥐고 있거나, 큐가 찼다 (#106).
 *
 * <p>409 다. 요청이 틀린 것이 아니라 <b>정상 상태</b>이고, 잠시 뒤 다시 부르면 된다 —
 * {@code ScanAlreadyRunningException} 과 같은 모양이다.
 */
public class ImplementationAlreadyRunningException extends RuntimeException {

    public enum Reason {
        /** 이 후보의 착수가 이미 진행 중이다 */
        ALREADY_RUNNING,
        /** 🔴 같은 저장소의 다른 후보가 워크스페이스를 쥐고 있다 — 겹치면 서로의 트리를 지운다 */
        REPOSITORY_BUSY,
        /** 착수 큐가 찼다 — 후보는 SELECTED 로 되돌아갔다 */
        QUEUE_FULL
    }

    private final Long candidateId;
    private final Reason reason;

    public ImplementationAlreadyRunningException(Long candidateId, Reason reason) {
        super(messageOf(candidateId, reason));
        this.candidateId = candidateId;
        this.reason = reason;
    }

    public Long candidateId() {
        return candidateId;
    }

    public Reason reason() {
        return reason;
    }

    private static String messageOf(Long candidateId, Reason reason) {
        return switch (reason) {
            case ALREADY_RUNNING -> "착수가 이미 진행 중입니다 candidateId=" + candidateId
                    + " — GET /api/candidates/" + candidateId + "/implement 로 진행을 봅니다";
            case REPOSITORY_BUSY -> "같은 저장소의 다른 후보가 착수 중입니다 candidateId=" + candidateId
                    + " — 워크스페이스는 저장소당 하나라 겹쳐 돌리지 않습니다. 끝난 뒤 다시 요청하세요";
            case QUEUE_FULL -> "착수 큐가 가득 찼습니다 candidateId=" + candidateId
                    + " — 후보는 SELECTED 로 되돌렸습니다. 잠시 뒤 다시 요청하세요";
        };
    }
}
