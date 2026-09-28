package com.ossagent.repository.application;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장소별 스캔 주기를 설정한다 — #108 · #26 FR-1.
 *
 * <p>{@code scan_interval_minutes} 컬럼은 #26 이 만들었지만 <b>설정할 API 가 없어</b> 운영에서
 * 도달 불가였다. {@code null} 로 두면 {@code scan.default-interval} 로 돌아간다.
 */
@Service
public class ConfigureScanIntervalUseCase {

    private final OssRepositoryRepository repositories;

    public ConfigureScanIntervalUseCase(OssRepositoryRepository repositories) {
        this.repositories = repositories;
    }

    /** @param minutes {@code null} 이면 기본 주기로 되돌린다 */
    @Transactional
    public OssRepository configure(Long repositoryId, Integer minutes) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수입니다");
        }
        if (minutes != null && minutes < 1) {
            throw new IllegalArgumentException("스캔 주기는 1분 이상이어야 합니다: " + minutes);
        }
        OssRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
        repository.updateScanInterval(minutes);
        return repositories.save(repository);
    }
}
