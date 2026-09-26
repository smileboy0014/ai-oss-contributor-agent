package com.ossagent.repository.domain;

/**
 * 저장소가 등록돼 있으나 <b>지금 스캔 대상이 아니다</b> — {@code enabled = false}.
 *
 * <p>🔴 {@link ScanAlreadyRunningException} 과 가르는 이유 — 그쪽은 「지금은 안 된다,
 * 잠시 뒤 다시」이고 이쪽은 「이 저장소는 보고 있지 않다」다. 대응이 다르다
 * (전자는 기다리면 되고, 후자는 사람이 저장소를 다시 켜야 한다).
 *
 * <p>스케줄러는 원래 {@code enabled} 를 보고 건너뛰었는데 API 경로만 보지 않고 있었다.
 * 두 진입점이 다르게 동작하면 「비활성화했는데 돈다」가 된다.
 */
public class RepositoryNotScannableException extends RuntimeException {

    public RepositoryNotScannableException(Long repositoryId) {
        super("스캔 대상이 아닌 저장소다: repositoryId=%d (enabled=false)".formatted(repositoryId));
    }
}
