package com.ossagent.repository.adapter.out.persistence;

import com.ossagent.repository.domain.RepositoryPolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 기여 규약 영속 어댑터.
 *
 * <p>자기 도메인의 Spring Data 인터페이스라 {@code repository} 의 UseCase 가 직접 주입받는다 —
 * {@code architecture.md} 의도적 완화 ②.
 */
public interface RepositoryPolicyRepository extends JpaRepository<RepositoryPolicy, Long> {

    /** 저장소당 정책은 1건이다 — {@code UNIQUE(repository_id)}. */
    Optional<RepositoryPolicy> findByRepositoryId(Long repositoryId);
}
