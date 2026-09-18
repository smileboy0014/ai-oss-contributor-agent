package io.github.smileboy0014.ossagent.repository;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OssRepositoryJpaRepository extends JpaRepository<OssRepository, Long> {
    boolean existsByUrl(String url);
}
