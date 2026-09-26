package com.ossagent.repository.domain;

/**
 * 같은 저장소의 스캔이 이미 진행 중이다 — #14 FR-4.
 *
 * <p>「거절 또는 병합」 중 <b>거절</b>을 택했다. 병합(진행 중인 실행에 합류)은 호출자에게
 * <b>남의 실행 결과</b>를 돌려주게 되고, 그 실행이 언제 시작했느냐에 따라 새 이슈가
 * 반영될 수도 안 될 수도 있다. <b>거절은 상태가 하나</b>다.
 */
public class ScanAlreadyRunningException extends RuntimeException {

    /** 왜 받지 못했는가 — 둘 다 「지금은 안 된다」이지만 원인이 다르다. */
    public enum Reason {
        /** 그 저장소가 이미 돌고 있다 */
        ALREADY_RUNNING,
        /** 🔴 큐가 찼다. 동시 1건(NFR-2) 제약의 결과다 */
        QUEUE_FULL
    }

    private final transient Reason reason;

    public ScanAlreadyRunningException(Long repositoryId, Reason reason) {
        super("스캔을 시작할 수 없다: repositoryId=%d reason=%s".formatted(repositoryId, reason));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
