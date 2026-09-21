package com.ossagent.repository.application;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryNotFoundException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스캔 요청 접수 경계.
 *
 * <p>현재는 요청 사실만 기록한다. 실제 이슈 수집은 {@code issue} 도메인의 스캐너가
 * 별도 트랜잭션에서 수행한다 — 대외 호출(GitHub API)을 이 트랜잭션 안에 두지 않는다.
 */
@Service
public class RequestScanUseCase {

    private final OssRepositoryRepository repositories;
    private final Clock clock;

    public RequestScanUseCase(OssRepositoryRepository repositories, Clock clock) {
        this.repositories = repositories;
        this.clock = clock;
    }

    @Transactional
    public Instant requestScan(Long repositoryId) {
        OssRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
        Instant requestedAt = clock.instant();
        repository.markScanned(requestedAt);
        return requestedAt;
    }
}
