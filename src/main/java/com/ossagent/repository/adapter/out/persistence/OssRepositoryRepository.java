package com.ossagent.repository.adapter.out.persistence;

import com.ossagent.repository.domain.OssRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OssRepositoryRepository extends JpaRepository<OssRepository, Long> {

    boolean existsByUrl(String url);

    /** 같은 저장소를 URL 철자만 다르게 두 번 등록하지 못하게 (#114). GitHub 좌표는 대소문자를 가리지 않는다 */
    boolean existsByOwnerIgnoreCaseAndNameIgnoreCase(String owner, String name);
}
