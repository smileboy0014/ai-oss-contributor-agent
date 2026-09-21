package com.ossagent.repository.application;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryAlreadyRegisteredException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대상 저장소 등록·조회. 트랜잭션 경계는 이 UseCase 다.
 */
@Service
public class RegisterRepositoryUseCase {

    private final OssRepositoryRepository repositories;

    public RegisterRepositoryUseCase(OssRepositoryRepository repositories) {
        this.repositories = repositories;
    }

    @Transactional
    public OssRepository register(String owner, String name, String url) {
        if (repositories.existsByUrl(url)) {
            throw new RepositoryAlreadyRegisteredException(url);
        }
        return repositories.save(new OssRepository(owner, name, url));
    }

    @Transactional(readOnly = true)
    public List<OssRepository> findAll() {
        return repositories.findAll();
    }
}
