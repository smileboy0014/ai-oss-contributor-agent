package com.ossagent.repository.adapter.out.persistence;

import com.ossagent.repository.domain.OssRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OssRepositoryRepository extends JpaRepository<OssRepository, Long> {

    boolean existsByUrl(String url);
}
