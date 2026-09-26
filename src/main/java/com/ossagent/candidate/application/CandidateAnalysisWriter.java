package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.IssueAnalysis;
import java.time.Clock;
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
 *
 * <h2>🔴 유일한 정당한 호출자는 {@link AnalyzeIssuesUseCase} 다</h2>
 *
 * <p>이 빈만으로도 후보를 만들 수 있지만 <b>거기에는 S-5 게이트가 없다.</b>
 * 규약이 금지·보류인 저장소의 이슈가 후보가 되는 경로가 바로 그것이다.
 * 그래서 클래스를 <b>package-private</b> 으로 좁혔다 — 같은 패키지의 UseCase 만 닿는다.
 * (메서드는 {@code public}·package-private 무관하게 CGLIB 프록시가 붙지만, 클래스 가시성이
 * 좁으면 {@code candidate.application} 밖에서는 <b>주입 자체가 컴파일되지 않는다.</b>)
 *
 * <p>⚠️ #14 스케줄러·#24 컨트롤러가 붙을 때 가장 새기 쉬운 자리다. 그쪽에서 필요하면
 * <b>이 빈이 아니라 {@link AnalyzeIssuesUseCase} 를</b> 부른다.
 *
 * <p>⚠️ <b>메서드는 {@code public} 으로 남긴다.</b> 클래스가 이미 package-private 이라 가시성
 * 목적은 달성됐고, package-private 메서드의 {@code @Transactional} 은 프록시 방식에 따라
 * 조용히 안 걸릴 수 있다. 그러면 {@code completeAnalysis} 의 더티체킹이 통째로 사라지는데
 * <b>증상이 「값이 저장되지 않는다」뿐</b>이라 원인을 찾기 어렵다.
 */
@Component
class CandidateAnalysisWriter {

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
     * <h2>🔴 제약 위반을 <b>여기서 삼키지 않는다</b></h2>
     *
     * <p>원래 이 메서드가 {@code DataIntegrityViolationException} 을 잡아
     * {@code Optional.empty()} 를 돌려주게 짰다가 되돌렸다. Hibernate 가 제약 위반을
     * 변환하면서 <b>트랜잭션을 rollback-only 로 표시</b>하므로, 예외를 삼키고 정상 반환해도
     * {@code REQUIRES_NEW} 프록시가 빠져나가며 커밋을 시도하다
     * <b>{@code UnexpectedRollbackException}</b> 을 던진다. 그것은 호출자가 잡는 두 타입 어디에도
     * 해당하지 않아 <b>배치 전체를 죽인다</b> — NFR-4 와 정면으로 어긋난다.
     *
     * <p>그래서 예외를 그대로 <b>전파</b>하고, 트랜잭션 <b>밖</b>에 있는
     * {@code AnalyzeIssuesUseCase.analyzeOne} 이 잡아 건너뛴다.
     *
     * @return 새로 만든 후보 id
     * @throws DataIntegrityViolationException 다른 실행이 이미 이 이슈를 잡았다 — 정상 경로다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long beginAnalysis(Long issueId) {
        ContributionCandidate candidate = ContributionCandidate.discover(issueId, clock);
        candidate.startAnalysis(clock);
        return candidates.saveAndFlush(candidate).getId();
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
