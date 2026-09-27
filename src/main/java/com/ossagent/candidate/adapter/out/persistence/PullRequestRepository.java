package com.ossagent.candidate.adapter.out.persistence;

import com.ossagent.candidate.domain.PullRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Draft PR 영속 어댑터.
 *
 * <p>🔴 <b>{@code UNIQUE(candidate_id)} 가 멱등성의 마지막 층이다</b> — S-2.
 * 후보 상태 전이(낙관적 잠금)와 「활성 PR 조회」가 창을 좁히고, 그 둘을 빠져나간 동시
 * 요청은 여기서 제약 위반으로 죽는다. 셋이 함께 서야 남의 저장소에 PR 이 두 개 열리지 않는다.
 *
 * <p>⚠️ 조회 메서드를 늘릴 때 <b>목록 조회를 만들지 않는다.</b> PR 은 후보당 1건이라
 * 「후보로 찾는 것」 말고 필요한 축이 없고, 축이 늘면 애그리거트 밖에서 PR 을 다루는
 * 코드가 생긴다.
 */
public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {

    Optional<PullRequest> findByCandidateId(Long candidateId);
}
