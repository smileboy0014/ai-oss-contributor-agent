package com.ossagent.candidate.application;

import com.ossagent.candidate.domain.ImplementationPlan;
import com.ossagent.candidate.domain.ImplementationPlanner;
import com.ossagent.candidate.domain.PlanRejectedException;
import com.ossagent.candidate.domain.PlanVerdict;
import com.ossagent.candidate.domain.PlanValidator;
import com.ossagent.candidate.domain.PlanningInput;
import com.ossagent.candidate.domain.ScopeLimits;
import com.ossagent.issue.application.FindAnalyzableIssuesUseCase;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase;
import com.ossagent.repository.application.BuildRepositoryContextUseCase;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryContext;
import com.ossagent.repository.domain.SelectedFile;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 구현 계획을 세우고 <b>검증까지 통과한 것만</b> 돌려준다 — PRD §13 · 이슈 #16.
 *
 * <pre>
 *   후보 로드 (SELECTED · attempt = 0)
 *     ↓
 *   이슈 값 ← issue 도메인 UseCase
 *   컨텍스트 ← #15 BuildRepositoryContextUseCase   🔴 S-5 게이트가 그 안에 있다
 *   규약 값 ← repository 도메인 UseCase
 *     ↓
 *   ┌─ 계획 수립(LLM) ──▶ 검증(순수) ─ 통과 ─▶ 반환
 *   │                        │ 거부
 *   └── 사유를 프롬프트에 되먹여 재생성 ◀┘   (agent.plan.max-attempts)
 *     ↓ 상한 소진
 *   🔴 후보를 FAILED 로 — S-6. 「사람에게 넘기는 신호」
 * </pre>
 *
 * <h2>🔴 계획은 {@code IMPLEMENTING} 전이 <b>앞</b>이다</h2>
 * {@code ContributionCandidate.attempt} 의 javadoc 이 「{@code PLAN} 행의 {@code attempt} 가
 * 1 일 때 이 필드는 0」이라고 못 박았다. {@code startImplementing} 이 그 필드를 1 로 만들므로,
 * 전이 뒤에 계획하면 불변식이 깨지고 <b>비용 집계가 어긋난다.</b>
 *
 * <p>그래서 이 UseCase 는 <b>전이를 하지 않는다.</b> 계획을 돌려줄 뿐이고,
 * {@code startImplementing} 은 계획을 받은 쪽(#18·#21)이 부른다.
 * <b>예외는 실패 경로 하나</b> — 상한 소진은 여기서 {@code FAILED} 로 끝낸다(아래).
 *
 * <h2>🔴 트랜잭션 안에서 부르지 않는다</h2>
 * LLM 호출이 루프 안에 있다. {@code @Transactional} 을 붙이지 않았고, 호출자가 감싸는 것을
 * {@link #assertNoTransaction()} 이 막는다.
 */
@Service
public class PlanImplementationUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlanImplementationUseCase.class);

    private final CandidatePlanningWriter writer;
    private final FindAnalyzableIssuesUseCase issues;
    private final BuildRepositoryContextUseCase repositoryContexts;
    private final AnalyzeRepositoryPolicyUseCase policies;
    private final ImplementationPlanner planner;
    private final ImplementationPlanProperties properties;

    public PlanImplementationUseCase(CandidatePlanningWriter writer,
            FindAnalyzableIssuesUseCase issues,
            BuildRepositoryContextUseCase repositoryContexts,
            AnalyzeRepositoryPolicyUseCase policies,
            ImplementationPlanner planner,
            ImplementationPlanProperties properties) {
        this.writer = writer;
        this.issues = issues;
        this.repositoryContexts = repositoryContexts;
        this.policies = policies;
        this.planner = planner;
        this.properties = properties;
    }

    /**
     * 검증을 통과한 계획을 돌려준다.
     *
     * @throws PlanExhaustedException 🔴 상한을 소진했다. <b>후보는 이미 {@code FAILED}</b> 다
     * @throws com.ossagent.repository.domain.ContributionNotAllowedException
     *         규약 보류·금지 — #15 의 게이트가 던진다 (S-5)
     * @throws com.ossagent.agent.domain.LlmException 호출 자체가 실패했다
     */
    public ImplementationPlan plan(Long candidateId) {
        if (candidateId == null) {
            throw new IllegalArgumentException("후보 식별자는 필수다");
        }
        assertNoTransaction();

        CandidatePlanningWriter.PlanningSnapshot snapshot = writer.load(candidateId);
        AnalyzableIssue issue = issues.findOne(snapshot.issueId())
                .orElseThrow(() -> new IllegalStateException(
                        "후보가 가리키는 이슈가 없다 candidateId=" + candidateId));

        // 🔴 S-5 게이트가 이 호출 안에 있다 — 컨텍스트를 밖에서 받지 않는 이유다.
        //    파라미터로 받으면 게이트를 건너뛴 컨텍스트가 들어올 수 있다
        RepositoryContext context = repositoryContexts.build(issue);
        ContributionConstraints constraints = policies.constraintsOf(issue.repositoryId());
        ScopeLimits limits = scopeLimitsOf(snapshot);
        Set<String> shownPaths = shownPathsOf(context);

        PlanningInput input = new PlanningInput(issue, context, constraints, null);
        PlanVerdict lastVerdict = null;

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            ImplementationPlan plan = attemptPlan(candidateId, input, attempt);
            if (plan != null) {
                PlanVerdict verdict = PlanValidator.validate(
                        plan, shownPaths, context.isPartial(), constraints, limits);
                if (verdict.isPassed()) {
                    // 🔴 경로는 남긴다 — 영속화하지 않으므로(D-2) 로그가 유일한 기록이다
                    log.info("구현 계획 확정 candidateId={} attempt={}/{} files={} loc={} paths={}",
                            candidateId, attempt, properties.maxAttempts(), plan.fileCount(),
                            plan.estimatedLoc(), plan.paths());
                    return plan;
                }
                lastVerdict = verdict;
            } else {
                // 스키마 위반도 재생성 대상이다 — 모델이 JSON 을 한 번 잘못 냈다고
                // 후보를 종단으로 보내지 않는다
                lastVerdict = PlanVerdict.rejected(java.util.List.of(
                        "직전 응답이 요구한 JSON 스키마를 만족하지 못했다. 형식을 정확히 지킨다"));
            }
            log.warn("구현 계획 거부 candidateId={} attempt={}/{} violations={}",
                    candidateId, attempt, properties.maxAttempts(), lastVerdict.violations().size());
            input = input.withFeedback(lastVerdict);
        }

        // 🔴 S-6 — 상한 소진은 FAILED 이고 그 자체가 사람에게 넘기는 신호다.
        //    여기서 전이하지 않으면 후보가 SELECTED 에 박혀 아무도 모른다
        // 🔴 별도 빈을 거친다 — 같은 클래스의 @Transactional 은 프록시를 타지 않는다
        writer.failPlanning(candidateId);
        log.error("구현 계획 상한 소진 candidateId={} — FAILED 로 종료한다 (S-6)", candidateId);
        throw new PlanExhaustedException(candidateId, properties.maxAttempts(),
                lastVerdict == null ? java.util.List.of() : lastVerdict.violations());
    }

    /** 스키마 위반은 {@code null} 로 돌려 호출부의 재생성 루프에 태운다 */
    private ImplementationPlan attemptPlan(Long candidateId, PlanningInput input, int attempt) {
        try {
            return planner.plan(candidateId, input);
        } catch (PlanRejectedException e) {
            // ⚠ 메시지를 로그 포맷에 넣지 않는다 — 모델 응답 조각이 섞일 수 있다
            log.warn("계획 응답이 스키마를 만족하지 못했다 candidateId={} attempt={}",
                    candidateId, attempt);
            return null;
        }
    }

    /**
     * 범위 눈금 둘 — D-6.
     *
     * <p>이슈별 추정치는 #11 이 적재한 것이다. 없으면 {@link ScopeLimits} 가 그 검사를
     * 건너뛴다 — 모르는 것을 최강 제약으로 번역하지 않는다.
     */
    private ScopeLimits scopeLimitsOf(CandidatePlanningWriter.PlanningSnapshot snapshot) {
        return new ScopeLimits(properties.maxPlannedFiles(), properties.maxPlannedLoc(),
                snapshot.estimatedFiles(), snapshot.estimatedLoc(),
                properties.scopeToleranceValue());
    }

    /** 검증이 실재를 판정하는 기준 — <b>모델에게 보여준 파일</b>이다 (D-5) */
    private static Set<String> shownPathsOf(RepositoryContext context) {
        Set<String> paths = new LinkedHashSet<>();
        for (SelectedFile file : context.files()) {
            paths.add(file.path());
        }
        return paths;
    }

    /** 🔴 LLM 호출이 트랜잭션 안에 들어가는 것을 막는다 */
    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "계획 수립을 트랜잭션 안에서 부를 수 없다 — LLM·GitHub 호출이 커넥션을 점유한다. "
                            + "호출자의 @Transactional 을 제거한다 (architecture.md 규율)");
        }
    }
}
