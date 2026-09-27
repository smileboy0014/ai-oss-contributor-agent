package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.adapter.out.persistence.GeneratedChangeRepository;
import com.ossagent.candidate.adapter.out.persistence.PullRequestRepository;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.GeneratedChange;
import com.ossagent.candidate.domain.PullRequest;
import com.ossagent.candidate.domain.StatusTransition;
import com.ossagent.support.secret.TokenRedactor;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Draft PR 생성의 <b>DB 쪽 절반</b> — #23.
 *
 * <h2>🔴 별도 빈인 이유는 self-invocation 이다</h2>
 *
 * <p>{@code CreateDraftPrUseCase} 가 자기 메서드에 {@code @Transactional} 을 달고 스스로
 * 부르면 <b>프록시를 타지 않아 애노테이션이 조용히 무시된다.</b> 증상이 예외가 아니라
 * 「트랜잭션이 없다」라 눈에 띄지 않는다 — {@code CandidatePlanningWriter} 가 같은 이유로
 * 갈라져 있고 그 선례를 그대로 따른다.
 *
 * <p>그리고 갈라 두면 <b>대외 호출이 어느 트랜잭션에도 들어갈 수 없다</b>는 것이 구조로
 * 보인다. UseCase 본체에 {@code @Transactional} 이 없고, 트랜잭션은 이 빈의 메서드 <b>안</b>
 * 에서 시작해서 끝난다.
 */
@Component
class CandidatePrWriter {

    private static final Logger log = LoggerFactory.getLogger(CandidatePrWriter.class);

    private static final String MDC_CANDIDATE_ID = "candidateId";

    private final ContributionCandidateRepository candidates;
    private final GeneratedChangeRepository changes;
    private final PullRequestRepository pullRequests;
    private final Clock clock;

    CandidatePrWriter(ContributionCandidateRepository candidates, GeneratedChangeRepository changes,
            PullRequestRepository pullRequests, Clock clock) {
        this.candidates = candidates;
        this.changes = changes;
        this.pullRequests = pullRequests;
        this.clock = clock;
    }

    /**
     * PR 을 만들기 위해 필요한 값을 <b>값으로</b> 꺼낸다.
     *
     * <p>엔티티를 트랜잭션 밖으로 들고 나가지 않는다 — {@code pullRequest} 가 LAZY 라
     * 밖에서 건드리면 {@code LazyInitializationException} 이고, 그것은 대외 호출 도중에
     * 터진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PrSnapshot load(Long candidateId) {
        ContributionCandidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));

        Optional<GeneratedChange> change =
                changes.findFirstByCandidateIdOrderByCreatedAtDesc(candidateId);

        return new PrSnapshot(
                candidate.getIssueId(),
                candidate.getStatus(),
                change.map(GeneratedChange::getBranchName).orElse(null),
                change.map(GeneratedChange::getCommitSha).orElse(null),
                // diff 는 record(...) 에서 이미 스크럽됐다 — Fork 에 입힐 변경분의 정본
                change.map(GeneratedChange::getDiff).orElse(null),
                // 🔴 읽는 자리에서 스크럽한다 — S-4. 아래 javadoc 참조
                scrub(change.map(GeneratedChange::getTestResult).orElse(null)),
                scrub(change.map(GeneratedChange::getReviewResult).orElse(null)),
                pullRequests.findByCandidateId(candidateId)
                        .map(PullRequest::getGithubPrNumber).orElse(null));
    }

    /**
     * Fork push 가 <b>성공한 뒤</b> 그 커밋을 최신 변경분 행에 남긴다 — 짧은 트랜잭션 (S-1 · #23).
     *
     * <p>PR 생성(다음 대외 호출)이 실패해도 이 기록은 남는다. 다음 호출이 그것을 보고
     * 「우리가 이미 올린 브랜치」라 판단해 <b>갱신</b>으로 다시 push 한다 — 새 브랜치 생성 시도가
     * 422 로 죽는 것을 막는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPublished(Long candidateId, String commitSha) {
        GeneratedChange change = changes.findFirstByCandidateIdOrderByCreatedAtDesc(candidateId)
                .orElseThrow(() -> new IllegalStateException(
                        "push 했는데 변경분 행이 없다 candidateId=" + candidateId));
        change.markPublished(commitSha);
        changes.saveAndFlush(change);
    }

    /**
     * 🔴 <b>PR 이 실제로 만들어진 뒤에만 불린다</b> — FR-9.
     *
     * <p>{@code PR_CREATED} 는 종단이라 되돌릴 수 없다. 대외 호출보다 먼저 전이를 커밋하면
     * 「PR 이 있다고 기록됐는데 없는」 후보가 <b>빠져나올 수 없는 상태로</b> 남는다.
     *
     * <p>🔴 <b>PullRequest 를 먼저 만들고 전이한다.</b> 엔티티가 양방향을 함께 채우므로
     * 「{@code PR_CREATED} 인데 PR 행이 없다」가 같은 트랜잭션 안에서 불가능해진다 — 불변식 ③.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StatusTransition attachPullRequest(Long candidateId, String forkUrl, String branchName,
            int prNumber, String prUrl) {

        ContributionCandidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new CandidateNotFoundException(candidateId));

        PullRequest pullRequest =
                PullRequest.draftFor(candidate, forkUrl, branchName, prNumber, prUrl, clock);

        StatusTransition transition;
        try {
            transition = candidate.markPrCreated(pullRequest, clock);
        } catch (RuntimeException e) {
            // 🔴 거부는 여기서 남긴다. 아래 afterCommit 은 롤백 경로에서 돌지 않는다
            //    ⚠ 예외 메시지는 우리 어휘다 — 대상 저장소 텍스트가 들어오지 않는다
            log.warn("승인 게이트 거부 gate=PR 생성 candidateId={} status={} reason={}",
                    candidateId, candidate.getStatus(), e.getMessage());
            throw e;
        }

        // 🔴 saveAndFlush 로 UNIQUE(candidate_id) 위반을 이 트랜잭션 안에서 드러낸다.
        //    지연 flush 로 두면 제약 위반이 커밋 시점에 터져 위 catch 를 빠져나간다
        pullRequests.saveAndFlush(pullRequest);
        candidates.saveAndFlush(candidate);

        logAfterCommit(candidateId, prNumber, prUrl, transition);
        return transition;
    }

    /**
     * 🔴 커밋 확정 후 게이트 통과를 남긴다 — S-6 · {@code logging.md} 「통과한 것도 남긴다」.
     *
     * <p>⚠️ 콜백 안의 예외는 <b>이미 커밋된 승인을 되돌리지 않는다.</b> 로깅 실패가 승인
     * 행위를 무르게 할 수는 없으므로 삼키되, <b>삼켰다는 사실을 남긴다.</b>
     */
    private void logAfterCommit(Long candidateId, int prNumber, String prUrl,
            StatusTransition transition) {

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("트랜잭션 동기화가 없다 — 커밋 확정 여부를 알 수 없는 채로 남긴다 gate=PR 생성");
            logGatePassed(candidateId, prNumber, prUrl, transition);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    logGatePassed(candidateId, prNumber, prUrl, transition);
                } catch (RuntimeException e) {
                    log.warn("게이트 통과 기록에 실패했다 — PR 은 이미 만들어졌다 candidateId={}",
                            candidateId, e);
                }
            }
        });
    }

    private void logGatePassed(Long candidateId, int prNumber, String prUrl,
            StatusTransition transition) {

        // ⚠ 본문·제목을 싣지 않는다 (S-4 · logging.md). 번호와 URL 은 우리가 만든 식별자다
        MDC.put(MDC_CANDIDATE_ID, String.valueOf(candidateId));
        try {
            log.info("승인 게이트 통과 gate=PR 생성 candidateId={} prNumber={} prUrl={} {} → {} "
                            + "(종단 — 되돌릴 수 없다)",
                    candidateId, prNumber, prUrl, transition.from(), transition.to());
        } finally {
            MDC.remove(MDC_CANDIDATE_ID);
        }
    }

    /**
     * 🔴 <b>DB 에서 읽는 자리에서 스크럽한다</b> — S-4.
     *
     * <h2>왜 여기인가 — {@code PrBody} 가 이미 하는데</h2>
     *
     * <p>{@code GeneratedChange.testResult}·{@code reviewResult} 는 오랫동안
     * {@code ExternalTextScrubRegistryTest} 에 <b>{@code PENDING}</b> 으로 올라 있었다 —
     * 적재 쪽에 강제 지점이 없어 DB 에 원문이 들어 있을 수 있었고, 유일한 스크럽은
     * {@code PrBody} 생성자였다(<b>값이 UseCase 를 통과한 뒤</b>). 지금은
     * {@code recordVerification}·{@code recordReview} 가 적재 쪽 강제 지점이지만,
     * 그 전에 앉은 행이 있을 수 있어 읽는 자리의 스크럽을 <b>지우지 않는다.</b>
     *
     * <p>그 사이 구간에서 값은 {@code PrSnapshot} 에 <b>원문으로</b> 앉아 있었고,
     * 그것을 {@code CandidateResponseRuleTest} 가 잡았다. 그 가드의 축은 「{@code application}
     * 패키지의 record 가 외부 텍스트 이름의 컴포넌트를 갖는가」이고, <b>축이 옳았다</b> —
     * 누가 이 값을 로그에 찍거나 뷰로 내보내면 그대로 나간다.
     *
     * <p>🔴 <b>고친 방식이 「가드 우회」가 아닌 이유</b>: 허용목록에 올리지도, 가드를 좁히지도
     * 않았다. <b>값 자체를 스크럽</b>해 위험을 없앴고, 컴포넌트 이름이 그 사실을 말하게 했다.
     * {@code safety-boundaries.md} 의 판별표에서 「위험이 사라지면 통과」에 해당한다.
     *
     * <p>⚠️ {@code PrBody} 의 스크럽을 <b>지우지 않는다.</b> 그쪽은 대상 저장소 템플릿까지
     * 함께 거르는 <b>마지막 그물</b>이고, 이것은 <b>더 이른 그물</b>이다. 두 벌이 아니라
     * 순서가 다른 방어다.
     *
     * @param scrubbedVerification 샌드박스 검증 출력. <b>이미 스크럽됐다</b>
     * @param scrubbedReview       AI 리뷰 텍스트. 〃
     * @param existingPrNumber     이미 붙어 있는 PR 번호. <b>{@code null} 이 정상</b>이다
     */
    /**
     * @param commitSha   Fork 에 올라간 커밋. {@code null} 이면 <b>아직 push 하지 않았다</b> — PR 생성기가
     *                    이 값으로 「새 브랜치 생성」과 「우리 브랜치 갱신」을 가른다
     * @param unifiedDiff 검증을 통과한 변경분(스크럽 후). Fork 에 올릴 파일은 이것을 새 워크스페이스에
     *                    입혀 만든다 — 착수 때의 디렉토리를 다시 읽지 않는다
     */
    record PrSnapshot(Long issueId, CandidateStatus status, String branchName, String commitSha,
                      String unifiedDiff, String scrubbedVerification, String scrubbedReview,
                      Integer existingPrNumber) {
    }

    private static String scrub(String raw) {
        return raw == null ? null : TokenRedactor.redact(raw);
    }
}
