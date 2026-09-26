package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.ContributionCandidate;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계획 단계의 <b>짧은 트랜잭션들</b> — 이슈 #16. LLM 루프 앞뒤로 하나씩이다.
 *
 * <h2>🔴 왜 UseCase 에서 분리했나</h2>
 * {@code CandidateAnalysisWriter}(#11)와 <b>같은 이유</b>다. 계획 수립은 LLM 호출을 최대
 * 2회 돌고 그 앞에 #15 의 GitHub 호출이 붙는다. 그 전체를 한 트랜잭션에 넣으면 커넥션이
 * 그만큼 잡힌다.
 *
 * <p>그리고 결정적으로 — <b>같은 클래스의 {@code @Transactional} 메서드를 부르면 아무 일도
 * 일어나지 않는다</b>(self-invocation). 프록시를 거치려면 별도 빈이어야 한다.
 * ⚠️ 이 PR 초안이 정확히 그 실수를 했다: {@code PlanImplementationUseCase} 안에
 * {@code @Transactional protected failCandidate} 를 두고 같은 클래스에서 불렀다.
 * <b>증상이 「상태가 저장되지 않는다」뿐</b>이고, 단위 테스트는 인메모리 엔티티를 단언해
 * <b>초록이었다.</b>
 *
 * <p>{@code REQUIRES_NEW} 인 이유 — 호출자가 실수로 트랜잭션을 감싸도 이 쓰기는 독립적으로
 * 커밋된다. <b>S-6 의 「사람에게 넘기는 신호」가 남의 롤백에 휩쓸리면 안 된다.</b>
 *
 * <h2>클래스를 package-private 으로 좁힌다 — <b>의도 표기이지 봉인이 아니다</b></h2>
 * 이 빈만으로 후보를 {@code FAILED} 로 보낼 수 있지만, <b>거기에는 「계획을 정말 못 세웠나」라는
 * 판단이 없다.</b> 종단 상태로 보내는 것은 되돌릴 수 없으므로 호출 지점을 좁힌다 —
 * {@code candidate.application} 밖에서는 주입 자체가 컴파일되지 않는다.
 *
 * <p>⚠️ <b>그러나 이것을 방어로 세지 않는다.</b> 같은 패키지에 클래스를 하나 추가하면
 * 누구나 주입받을 수 있다(JPMS 를 쓰지 않는다). S-1 이 「쓰기 메서드를 만들지 않았으니
 * 안전하다」를 방어로 세지 않기로 한 것과 같은 이유다 — <b>좁은 표면은 의도 표기로는
 * 가치가 있지만 강제력이 아니다.</b> 막힌다고 적으면 거짓 안전감만 남고,
 * 다음 사람이 확인을 건너뛴다.
 *
 * <p>⚠️ <b>메서드는 {@code public} 으로 남긴다.</b> package-private 메서드의
 * {@code @Transactional} 은 프록시 방식에 따라 조용히 안 걸릴 수 있다 — 그러면 이 클래스를
 * 만든 이유가 통째로 사라진다.
 */
@Component
class CandidatePlanningWriter {

    private final ContributionCandidateRepository candidates;
    private final Clock clock;

    CandidatePlanningWriter(ContributionCandidateRepository candidates, Clock clock) {
        this.candidates = candidates;
        this.clock = clock;
    }

    /**
     * 계획 재생성 상한을 소진했다 — {@code SELECTED → FAILED} (종단). S-6.
     *
     * <p>🔴 <b>더티체킹에 기대지 않고 명시적으로 저장한다.</b> 이 전이가 저장되지 않으면
     * 후보가 {@code SELECTED} 로 남아 <b>「사람에게 넘기는 신호」가 발생하지 않는다</b> —
     * 이 메서드가 존재하는 이유가 바로 그것을 막는 것이다. 조용히 실패할 수 있는 형태로
     * 두지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failPlanning(Long candidateId) {
        ContributionCandidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));
        candidate.failPlanning(clock);
        candidates.saveAndFlush(candidate);
    }

    /** 계획에 필요한 후보 정보만 읽는다 — 엔티티를 트랜잭션 밖으로 들고 나가지 않는다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PlanningSnapshot load(Long candidateId) {
        ContributionCandidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));
        return new PlanningSnapshot(candidate.getIssueId(), candidate.getEstimatedFiles(),
                candidate.getEstimatedLoc());
    }

    /**
     * 후보에서 읽어 올 것만.
     *
     * @param estimatedFiles 분석이 추정한 파일 수 (#11). 범위 검사의 이슈별 눈금이다
     * @param estimatedLoc   〃 라인 수
     */
    record PlanningSnapshot(Long issueId, Integer estimatedFiles, Integer estimatedLoc) {
    }
}
