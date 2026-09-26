package com.ossagent.candidate.adapter.out.persistence;

import com.ossagent.candidate.domain.GeneratedChange;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 생성 변경분 영속 어댑터.
 *
 * <p>⚠️ <b>재시도마다 새 행이 쌓인다</b>(Q-6 상한 3이므로 최대 3행). 상세 조회는 <b>최신 1건만</b>
 * 읽는다 — 전건을 읽으면 {@code diff} 수십 KB × 3 이라 「목록에서 TEXT 를 읽지 않는다」는 규율의
 * 예외 근거가 무너진다. {@code (candidate_id, created_at)} 인덱스가 받친다.
 *
 * <p>대가는 <b>실패한 이전 시도의 diff 해시가 보이지 않는 것</b>이다. 무엇이 왜 실패했는지는
 * {@code AgentRun} 쪽에 남아 있다.
 */
public interface GeneratedChangeRepository extends JpaRepository<GeneratedChange, Long> {

    Optional<GeneratedChange> findFirstByCandidateIdOrderByCreatedAtDesc(Long candidateId);

    long countByCandidateId(Long candidateId);
}
