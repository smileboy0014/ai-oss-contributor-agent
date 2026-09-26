package com.ossagent.candidate.adapter.out.persistence;

import com.ossagent.candidate.domain.AgentRun;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 실행 이력 영속 어댑터.
 *
 * <p>자기 도메인의 Spring Data 인터페이스라 {@code candidate} 의 UseCase 가 직접 주입받는다 —
 * {@code architecture.md} 의도적 완화 ②.
 */
public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    /**
     * 후보의 실행 이력 — 오래된 순.
     *
     * <p>파이프라인을 따라 읽는 것이라 시간 순이 자연스럽다. {@code (candidate_id, stage, attempt)}
     * 인덱스가 받친다.
     *
     * <p>⚠️ {@code errorMessage} 가 함께 온다. 응답으로 내보내기 전 <b>반드시 스크럽</b>한다 — S-4.
     */
    List<AgentRun> findByCandidateIdOrderByStartedAtAsc(Long candidateId);
}
