package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.ImplementationNotReadyException;
import com.ossagent.candidate.domain.StatusTransition;
import com.ossagent.issue.application.FindAnalyzableIssuesUseCase;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase;
import com.ossagent.repository.domain.PolicyClearance;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 착수 게이트의 <b>트랜잭션 구간</b>만 담는다 — #18 · S-5 · S-6.
 *
 * <h2>🔴 왜 별도 빈인가 — self-invocation 함정</h2>
 *
 * <p>{@code @Transactional} 은 프록시로 걸리므로 <b>같은 빈 안에서 부르면 적용되지 않는다.</b>
 * 증상이 예외가 아니라 <b>「설정이 조용히 무시된다」</b>라 눈에 띄지 않는다 —
 * {@code AnalyzeRepositoryPolicyUseCase} 가 같은 함정을 주석으로 남겨 뒀고,
 * #11·#16 은 그것 때문에 <b>「저장이 사라진다」</b>를 겪었다.
 *
 * <p>{@code ImplementCandidateUseCase} 는 <b>트랜잭션 없이</b> 오케스트레이션하고
 * (샌드박스 최대 30분 · LLM 호출), 트랜잭션이 필요한 조각만 이 빈을 통해 부른다.
 *
 * <h2>🔴 통행증을 이 트랜잭션 안에서 발급하고 쓴다 (TOCTOU)</h2>
 *
 * <p>통행증은 <b>스냅샷</b>이다. 발급과 사용 사이에 정책이 바뀔 수 있으므로 같은
 * 트랜잭션 안에서 쓴다. 🔴 단 <b>샌드박스 실행은 이 트랜잭션 밖</b>이다 — 최대 30분짜리
 * 실행을 트랜잭션에 넣으면 커넥션이 30분 잡힌다.
 *
 * <h2>🔴 통행증은 <b>후보의 저장소</b>로만 조회한다</h2>
 *
 * <p>{@code startImplementing} 은 통행증이 이 후보의 것인지 <b>검증하지 못한다</b> —
 * 후보가 {@code issueId} 만 들고 있어 자기 {@code repositoryId} 를 모르기 때문이다.
 * 발급 경로가 {@code clearanceFor(repositoryId)} 하나뿐이므로 <b>후보의 저장소로
 * 조회하기만 하면 어긋날 수 없고, 그 한 줄이 이 이슈의 몫</b>이다.
 *
 * <p>⚠️ 그래서 {@code repositoryId} 를 <b>파라미터로 받지 않는다.</b> 받으면 호출자가
 * 다른 저장소의 통행증을 끌어올 수 있고, 그 순간 이 불변식이 호출자 신뢰에 기댄다.
 */
@Component
class CandidateImplementationWriter {

    private static final Logger log = LoggerFactory.getLogger(CandidateImplementationWriter.class);

    private final ContributionCandidateRepository candidates;
    private final FindAnalyzableIssuesUseCase issues;
    private final AnalyzeRepositoryPolicyUseCase policies;
    private final ExecutionProperties properties;
    private final Clock clock;

    CandidateImplementationWriter(ContributionCandidateRepository candidates,
            FindAnalyzableIssuesUseCase issues,
            AnalyzeRepositoryPolicyUseCase policies,
            ExecutionProperties properties,
            Clock clock) {
        this.candidates = candidates;
        this.issues = issues;
        this.policies = policies;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * {@code SELECTED → IMPLEMENTING} — 🔴 <b>S-6 두 번째 승인 게이트</b>.
     *
     * <p>순서가 중요하다 — <b>통행증을 먼저 받고</b> 전이한다. 반대로 하면 정책 위반
     * 후보가 잠깐이라도 {@code IMPLEMENTING} 이 되고, 그 사이에 다른 경로가 그것을 본다.
     *
     * @throws com.ossagent.repository.domain.ContributionNotAllowedException
     *         보류·금지 저장소 — {@code ApiExceptionHandler} 가 <b>403</b> 으로 매핑한다 (S-5)
     */
    /**
     * @param executorReady 🔴 실행기가 배선됐는가. <b>순서가 중요하다</b> — 이 값은
     *                      <b>통행증 확인 뒤에</b> 본다. 앞에서 보면 정책이 막았어야 할
     *                      요청이 <b>503 으로 가려져</b> S-5 게이트가 한 번도 돌지 않는다.
     *                      그러면 「막는다」를 검증할 수 없고, 나중에 실행기가 붙는 순간
     *                      <b>그때 처음으로</b> 정책 경로가 실행된다
     */
    @Transactional
    ImplementationStart start(Long candidateId, boolean executorReady) {
        ContributionCandidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));

        AnalyzableIssue issue = issues.findOne(candidate.getIssueId())
                .orElseThrow(() -> new IllegalStateException(
                        "후보가 가리키는 이슈가 없다 candidateId=" + candidateId));

        // 🔴 후보의 저장소로만 부른다 — 위 javadoc 의 불변식
        PolicyClearance clearance;
        try {
            clearance = policies.clearanceFor(issue.repositoryId());
        } catch (RuntimeException e) {
            // 🔴 거부는 여기서 남긴다. 아래 afterCommit 은 롤백 경로에서 돌지 않으므로
            //    거기 두면 「막았다」는 기록이 영영 남지 않는다
            log.warn("승인 게이트 거부 gate=착수 candidateId={} repositoryId={} reason={}",
                    candidateId, issue.repositoryId(), e.getMessage());
            throw e;
        }

        // 🔴 **판정을 먼저, 전이는 나중에.** 순서가 셋 다 중요하다.
        //
        //   ① 통행증 → 이 판정. 반대면 정책이 막았어야 할 요청이 아래 503 에 가려져
        //      S-5 게이트가 한 번도 돌지 않는다.
        //   ② 이 판정 → 실행기 검사. 반대면 「고르지 않은 후보」(409)가 503 에 가려진다 —
        //      요청이 틀린 것을 우리 사정으로 덮는 셈이다.
        //   ③ 실행기 검사 → 전이. 🔴 **전이를 하지 않으므로 롤백에 기대지 않는다.**
        //
        //   ⚠ 초안은 전이한 뒤 던져 롤백에 맡겼다. 작동은 했지만(부분 커밋 경로 없음을 확인)
        //     그 구간에 들어오는 **모든 부작용이 트랜잭션을 알아야 한다**는 제약이 생기고,
        //     코드가 그 제약을 말해주지 않는다 — 메트릭·이벤트는 롤백되지 않는다.
        //     「판정과 전이가 한 메서드에 묶여 어쩔 수 없다」는 **사실이 아니었다.**
        try {
            candidate.assertCanStartImplementing(clearance, properties.maxRetries());
        } catch (RuntimeException e) {
            // 🔴 거부는 여기서 남긴다 — afterCommit 은 롤백 경로에서 돌지 않으므로
            //    거기 두면 「막았다」는 기록이 영영 남지 않는다
            log.warn("승인 게이트 거부 gate=착수 candidateId={} status={} reason={}",
                    candidateId, candidate.getStatus(), e.getMessage());
            throw e;
        }

        if (!executorReady) {
            log.warn("착수 중단 — 실행기 미배선 candidateId={} (전이하지 않는다)", candidateId);
            throw new ImplementationNotReadyException(
                    "코딩 에이전트와 산출 경로가 아직 배선되지 않았다 (#18 의 C·D)");
        }

        StatusTransition transition =
                candidate.startImplementing(clearance, properties.maxRetries(), clock);

        logAfterCommit(candidateId, transition);
        return new ImplementationStart(candidateId, issue.repositoryId(),
                issue.githubIssueNumber(), candidate.getAttempt(), transition);
    }

    /**
     * 🔴 후보를 {@code FAILED} 로 떨어뜨린다 — <b>종단이고, 그 자체가 사람에게 넘기는 신호</b>다 (S-6).
     *
     * <p>별도 트랜잭션인 이유 — 실패는 <b>트랜잭션 밖에서 일어난 일</b>(샌드박스·LLM)에
     * 대한 기록이다. 원래 트랜잭션은 이미 커밋됐다.
     */
    @Transactional
    void fail(Long candidateId, String reason) {
        ContributionCandidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));
        candidate.fail(clock);
        // ⚠ 사유는 우리 어휘로만 남긴다. 대상 저장소 텍스트·빌드 출력을 그대로 넣으면
        //   S-4 대상이 되고 로그 인젝션 경로가 된다
        log.warn("후보 실패 candidateId={} reason={}", candidateId, reason);
    }

    /** 🔴 커밋 확정 후에만 게이트 통과를 남긴다 — {@code logging.md} 「통과한 것도 남긴다」. */
    private void logAfterCommit(Long candidateId, StatusTransition transition) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("트랜잭션 동기화가 없다 — 커밋 확정 여부를 알 수 없는 채로 남긴다 gate=착수");
            logGatePassed(candidateId, transition);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    logGatePassed(candidateId, transition);
                } catch (RuntimeException e) {
                    // 로깅 실패가 이미 커밋된 승인을 되돌릴 수는 없다. 삼키되 삼켰다는 사실을 남긴다
                    log.warn("게이트 통과 로그를 남기지 못했다 candidateId={}", candidateId, e);
                }
            }
        });
    }

    private void logGatePassed(Long candidateId, StatusTransition transition) {
        log.info("승인 게이트 통과 gate=착수 candidateId={} {} → {}",
                candidateId, transition.from(), transition.to());
    }

    /**
     * 트랜잭션 밖으로 나가는 값.
     *
     * <p>🔴 <b>엔티티를 내보내지 않는다.</b> 트랜잭션 밖에서 엔티티를 들고 다니면
     * 지연 로딩이 터지거나(detached) 우연히 살아 있는 영속 컨텍스트에 기대게 된다.
     */
    record ImplementationStart(Long candidateId, Long repositoryId, Integer issueNumber,
            int attempt, StatusTransition transition) {
    }
}
