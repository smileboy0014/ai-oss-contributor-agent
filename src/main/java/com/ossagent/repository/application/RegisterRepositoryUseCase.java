package com.ossagent.repository.application;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryAlreadyRegisteredException;
import com.ossagent.repository.domain.RepositoryCoordinates;
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
        // 🔴 좌표를 등록 시점에 검증한다 (#114). 여기서 안 보면 잘못된 문자가 201 로 들어가
        //    첫 스캔의 RepositoryCoordinates 에서야 죽고, 사람은 「스캔 실패」로 읽는다
        RepositoryCoordinates coordinates = new RepositoryCoordinates(owner, name);
        if (repositories.existsByUrl(url)) {
            throw new RepositoryAlreadyRegisteredException(url);
        }
        // 🔴 같은 저장소를 URL 철자만 다르게 두 번 등록하면 같은 upstream 이슈에 Draft PR 이 둘 나간다 (#114)
        if (repositories.existsByOwnerIgnoreCaseAndNameIgnoreCase(coordinates.owner(), coordinates.name())) {
            throw new RepositoryAlreadyRegisteredException(coordinates.fullName());
        }
        return repositories.save(new OssRepository(coordinates.owner(), coordinates.name(), url));
    }

    @Transactional(readOnly = true)
    public List<OssRepository> findAll() {
        return repositories.findAll();
    }
}
