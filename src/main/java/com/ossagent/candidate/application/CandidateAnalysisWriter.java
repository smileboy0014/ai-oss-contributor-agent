package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.IssueAnalysis;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 파이프라인의 <b>짧은 트랜잭션들</b>. LLM 호출 앞뒤로 하나씩이다.
 *
 * <h2>🔴 왜 UseCase 에서 분리했나</h2>
 *
 * <p>LLM 호출은 수십 초다. 배치 전체를 한 트랜잭션에 넣으면 커넥션이 그만큼 잡히고,
 * 샌드박스가 붙는 뒤 단계에서는 30분이 된다. <b>대외 호출은 트랜잭션 밖</b>이라는 규율을
 * 지키려면 쓰기를 별도 빈으로 빼서 프록시를 거치게 해야 한다 — 같은 클래스의 메서드를
 * 부르면 {@code @Transactional} 이 <b>아무 일도 하지 않는다</b>(self-invocation).
 *
 * <p>{@code REQUIRES_NEW} 인 이유 — 호출자가 실수로 트랜잭션을 감싸도 이 쓰기들은 각자
 * 커밋된다. 한 이슈의 실패가 앞서 성공한 후보들을 롤백시키면 안 된다(NFR-4).
 */
@Component
public class CandidateAnalysisWriter {

    private static final Logger log = LoggerFactory.getLogger(CandidateAnalysisWriter.class);

    private final ContributionCandidateRepository candidates;
    private final Clock clock;

    public CandidateAnalysisWriter(ContributionCandidateRepository candidates, Clock clock) {
        this.candidates = candidates;
        this.clock = clock;
    }

    /**
     * TX1 — 후보를 만들고 {@code ANALYZING} 으로 옮긴다.
     *
     * <h2>🔴 멱등의 정본은 {@code UNIQUE(issue_id)} 다</h2>
     *
     * <p>호출자의 사전 확인은 <b>LLM 호출을 아끼는 최적화</b>일 뿐이다. 두 워커가 동시에
     * 그 확인을 통과할 수 있고, 그때 한쪽을 떨어뜨리는 것은 DB 제약이다.
     * <b>그 예외를 정상 흐름으로 삼키는 것</b>이 멱등의 실체다.
     *
     * <p>{@code ANALYZING} 을 여기서 커밋하는 이유 — 이 중간 상태가 없으면 프로세스가 죽었을 때
     * 후보가 {@code DISCOVERED} 로 남아 <b>다음 실행이 같은 이슈에 토큰을 또 태운다.</b>
     *
     * @return 새로 만든 후보 id. 이미 다른 실행이 잡았으면 {@link Optional#empty()}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> beginAnalysis(Long issueId) {
        try {
            ContributionCandidate candidate = ContributionCandidate.discover(issueId, clock);
            candidate.startAnalysis(clock);
            return Optional.of(candidates.saveAndFlush(candidate).getId());
        } catch (DataIntegrityViolationException e) {
            // 경쟁에서 졌다 = 다른 실행이 이미 이 이슈를 잡았다. 정상이다
            log.debug("후보가 이미 있다 issueId={} — 건너뛴다", issueId);
            return Optional.empty();
        }
    }

    /**
     * TX2 — 분석 결과를 적재하고 {@code ANALYZED} 로 옮긴다. 임계 미달이면 이어서
     * {@code REJECTED} 까지 간다.
     *
     * <p>🔴 <b>{@code ANALYZED} 를 거쳐 가는 것이 의도다.</b> 상태머신이
     * {@code ANALYZING → REJECTED} 를 허용하지 않기도 하지만(#12), 무엇보다
     * <b>분석 결과가 어느 쪽이든 DB 에 남아야</b> 왜 걸러졌는지 보인다.
     *
     * @param reject {@code implementationFeasible=false} 또는 신뢰도 미달
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAnalysis(Long candidateId, IssueAnalysis analysis, boolean reject) {
        ContributionCandidate candidate = load(candidateId);
        candidate.completeAnalysis(analysis, clock);
        if (reject) {
            candidate.rejectAsInfeasible(clock);
        }
    }

    /**
     * TX2' — 분석이 실패했다. {@code ANALYZING → FAILED} <b>종단</b>이다 (Q-6).
     *
     * <p>파이프라인 재시도를 붙이지 않는다. 5xx·타임아웃은 전송 계층 축이 이미 흡수했고,
     * 여기까지 온 실패는 다시 보내도 같다. 상한 소진이 곧 <b>사람에게 넘기는 신호</b>다 (S-6).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failAnalysis(Long candidateId) {
        load(candidateId).failAnalysis(clock);
    }

    private ContributionCandidate load(Long candidateId) {
        return candidates.findById(candidateId)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));
    }
}
