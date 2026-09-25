package com.ossagent.candidate.adapter.out.persistence;

import com.ossagent.candidate.domain.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 실행 이력 영속 어댑터.
 *
 * <p>자기 도메인의 Spring Data 인터페이스라 {@code candidate} 의 UseCase 가 직접 주입받는다 —
 * {@code architecture.md} 의도적 완화 ②.
 */
public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {
}
