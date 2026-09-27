package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.UnwiredChangeVerifier;
import com.ossagent.candidate.domain.ChangeVerifier;
import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.TargetWorkspaceSource;
import com.ossagent.agent.domain.WorkspaceDiff;
import com.ossagent.candidate.domain.CodingAgent;
import com.ossagent.candidate.domain.CodingInput;
import com.ossagent.candidate.domain.CodingOutOfPlanException;
import com.ossagent.candidate.domain.GeneratedFile;
import com.ossagent.candidate.domain.ImplementationPlan;
import com.ossagent.candidate.domain.VerificationReport;
import com.ossagent.candidate.domain.VerificationRequest;
import com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase;
import com.ossagent.repository.application.BuildRepositoryContextUseCase;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryCoordinates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.ossagent.candidate.domain.StatusTransition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사람의 승인 지점 — <b>착수</b> (#18 · S-6 두 번째 게이트).
 *
 * <p>PRD §20 이 정한 승인 지점 셋 중 두 번째다. 첫 번째(선정)는
 * {@code SelectCandidateUseCase}, 세 번째(PR 생성)는 #23 이다.
 *
 * <h2>🔴 여기서 PR 까지 흘려보내지 않는다</h2>
 *
 * <p>PRD §24 시퀀스는 {@code implement} 호출 <b>하나가</b> Fork → 계획 → 코딩 → 테스트 →
 * 리뷰 → <b>Draft PR 생성까지</b> 수행하는 것으로 그려져 있다. <b>그 다이어그램이 틀렸다</b>(#30).
 * 그대로 구현하면 <b>세 번째 게이트가 사라지고 S-2 까지 뚫린다.</b>
 *
 * <p>이 UseCase 는 <b>{@code READY_FOR_PR} 이전에서 끝난다.</b> push·PR 생성 경로를 갖지 않는다 (S-1·S-2).
 *
 * <h2>🔴 트랜잭션 밖이다</h2>
 *
 * <p>샌드박스 실행은 <b>최대 30분</b>({@code agent.execution.timeout-seconds})이다.
 * 트랜잭션 안에 들어가면 커넥션이 30분 잡힌다 — {@code architecture.md} 가 이 규율을
 * 「특히 중요한 이유」로 이 숫자를 든다.
 *
 * <p>그래서 이 클래스에 {@code @Transactional} 이 <b>없고</b>, 트랜잭션이 필요한 조각은
 * {@link CandidateImplementationWriter} 를 통해 부른다. <b>같은 빈 안에서 부르면
 * 프록시가 적용되지 않는다</b>는 함정(self-invocation)을 구조로 피한 것이다.
 *
 * <h2>⚠️ 지금은 1바퀴만 돈다</h2>
 *
 * <p>{@code CODE → VERIFY → REVIEW} 루프와 {@code attempt} 상한 소진 판정은 <b>#21</b> 이다.
 * 여기서는 검증을 한 번 부르고, 실패하면 {@code FAILED} 로 떨어뜨린다.
 */
@Service
public class ImplementCandidateUseCase {

    private static final Logger log = LoggerFactory.getLogger(ImplementCandidateUseCase.class);

    private static final String MDC_CANDIDATE_ID = "candidateId";
    private static final String MDC_STAGE = "stage";
    private static final String MDC_ATTEMPT = "attempt";

    private final CandidateImplementationWriter writer;
    private final PlanImplementationUseCase planner;
    private final BuildRepositoryContextUseCase contexts;
    private final AnalyzeRepositoryPolicyUseCase policies;
    private final TargetWorkspaceSource workspaces;
    private final CodingAgent codingAgent;
    private final ChangeVerifier verifier;

    /**
     * 🔴 {@link ChangeVerifier} 를 {@link ObjectProvider} 로 받는다.
     *
     * <p>#19 가 실물을 넣기 전까지는 {@link UnwiredChangeVerifier} 가 쓰인다 —
     * <b>항상 실패</b>를 돌려주므로 후보가 {@code IMPLEMENTING} 에 갇히지 않고
     * {@code FAILED} 라는 <b>사람에게 넘기는 신호</b>가 된다.
     *
     * <p>⚠️ {@code @ConditionalOnMissingBean} 을 쓰지 않는다 — 자동설정 전용이라
     * 컴포넌트 스캔 빈에서는 평가 시점이 순서에 좌우돼 <b>조용히 어긋난다.</b>
     * 여기서 명시적으로 고르면 순서에 기대는 자리가 없다.
     */
    public ImplementCandidateUseCase(CandidateImplementationWriter writer,
            PlanImplementationUseCase planner,
            BuildRepositoryContextUseCase contexts,
            AnalyzeRepositoryPolicyUseCase policies,
            ObjectProvider<TargetWorkspaceSource> workspaces,
            ObjectProvider<CodingAgent> codingAgents,
            ObjectProvider<ChangeVerifier> verifiers) {
        this.writer = writer;
        this.planner = planner;
        this.contexts = contexts;
        this.policies = policies;
        // 🔴 셋 다 대역 프로필에서 빠질 수 있다(@ExternalAdapter · @Profile("!fakes")).
        //    getIfAvailable 로 받아 **없으면 착수를 시작하지 않는다** — executorReady() 참조
        this.workspaces = workspaces.getIfAvailable();
        this.codingAgent = codingAgents.getIfAvailable();
        this.verifier = verifiers.getIfAvailable(UnwiredChangeVerifier::new);
        if (this.verifier instanceof UnwiredChangeVerifier) {
            log.warn("ChangeVerifier 실물이 없다 — 닫히는 기본값으로 동작한다 (#19 대기)");
        }
    }

    /**
     * {@code SELECTED → IMPLEMENTING} 이후를 진행한다 — 🔴 <b>사람이 착수를 지시한다.</b>
     *
     * <p>호출자는 <b>web 어댑터뿐</b>이어야 한다. 스케줄러·워커가 이것을 부르면
     * 「사람이 착수를 지시한다」가 무너진다 — 아키텍처 테스트가 그 사실을 고정한다.
     *
     * @throws com.ossagent.repository.domain.ContributionNotAllowedException
     *         보류·금지 저장소 — <b>403</b> (S-5)
     */
    public StatusTransition implement(Long candidateId) {
        if (candidateId == null) {
            throw new IllegalArgumentException("후보 식별자는 필수입니다");
        }
        assertNoTransaction();

        // 🔴 판정은 여기서 하고 **막는 것은 writer 가** 한다 — 통행증 확인 뒤여야 하기 때문이다.
        //    여기서 바로 던지면 정책이 막았어야 할 요청이 503 으로 가려져 S-5 게이트가
        //    한 번도 돌지 않는다. 그러면 「막는다」를 검증할 수 없다
        CandidateImplementationWriter.ImplementationStart start =
                writer.start(candidateId, executorReady());

        MDC.put(MDC_CANDIDATE_ID, String.valueOf(candidateId));
        MDC.put(MDC_ATTEMPT, String.valueOf(start.attempt()));
        try {
            runOutsideTransaction(start);
        } finally {
            MDC.remove(MDC_CANDIDATE_ID);
            MDC.remove(MDC_STAGE);
            MDC.remove(MDC_ATTEMPT);
        }
        return start.transition();
    }

    /**
     * 🔴 트랜잭션 밖 구간 — 워크스페이스 · LLM · 샌드박스.
     *
     * <pre>
     * 계획(#16) → 워크스페이스 clone → 코딩(#18) → 파일 쓰기
     *   → diff → 🔴 경로 집합 게이트 → GeneratedChange 영속화 → 검증(#19)
     * </pre>
     *
     * <p>⚠️ <b>여기서 PR 로 가지 않는다.</b> 마지막이 검증이고, {@code READY_FOR_PR} 전환과
     * PR 생성은 각각 #21·#23 이다 (S-2·S-6).
     *
     * <h2>실패는 전부 {@code FAILED} 로 수렴한다 — 지금은</h2>
     *
     * <p>재시도 루프는 <b>#21</b> 이다. 이 PR 은 <b>1바퀴</b>만 돌리므로 어떤 실패든
     * 후보가 종단으로 간다 — 그 자체가 <b>사람에게 넘기는 신호</b>다 (S-6).
     *
     * <p>⚠️ 사유는 <b>우리 어휘로만</b> 남긴다. 대상 저장소 텍스트·빌드 출력·모델 응답을
     * 그대로 넣으면 S-4 대상이 되고 로그 인젝션 경로가 된다.
     */
    private void runOutsideTransaction(CandidateImplementationWriter.ImplementationStart start) {
        Long candidateId = start.candidateId();
        try {
            MDC.put(MDC_STAGE, "PLAN");
            ImplementationPlan plan = planner.plan(candidateId);

            MDC.put(MDC_STAGE, "CODE");
            RepositoryCoordinates coordinates = policies.coordinatesOf(start.repositoryId());
            ContributionConstraints constraints = policies.constraintsOf(start.repositoryId());

            String branch = branchName(start.issueNumber());
            SandboxWorkspace workspace = workspaces.fetch(coordinates, branch);

            List<GeneratedFile> generated = codingAgent.write(candidateId, start.attempt(),
                    new CodingInput(plan, contexts.build(start.issue()), constraints));
            writeToWorkspace(workspace, generated);

            WorkspaceDiff diff = workspaces.diff(workspace);
            assertWithinPlan(candidateId, diff, plan);

            Long changeId = writer.recordChange(candidateId, branch, diff.unifiedDiff());

            MDC.put(MDC_STAGE, "VERIFY");
            VerificationReport report = verifier.verify(new VerificationRequest(
                    candidateId, start.attempt(), workspace.path().toString(),
                    constraints, Set.copyOf(plan.paths())));

            if (!report.passed()) {
                // ⚠ 실패 사유는 #19 가 만든 값이다 — 우리 어휘로 요약하고 원문을 로그에 싣지 않는다
                log.warn("검증 실패 candidateId={} changeId={} reasons={}",
                        candidateId, changeId, report.failures().size());
                writer.fail(candidateId, "검증 실패 (사유 " + report.failures().size() + "건)");
                return;
            }
            // 🔴 여기서 READY_FOR_PR 로 보내지 않는다 — 그 전이는 #21 의 루프가 판정한다.
            //    지금 보내면 재시도 축이 없는 채로 세 번째 게이트 앞까지 흘러간다
            log.info("검증 통과 candidateId={} changeId={} — 이후 전이는 #21", candidateId, changeId);

        } catch (CodingOutOfPlanException e) {
            // 🔴 재시도 대상이 아니다 — 같은 계획·같은 프롬프트로 다시 부르면 같은 결과다
            log.warn("계획 밖 경로 candidateId={} count={}", candidateId, e.outsidePaths().size());
            writer.fail(candidateId, "모델이 계획 밖 경로를 돌려줬다 (" + e.outsidePaths().size() + "건)");
        } catch (RuntimeException e) {
            // ⚠ 예외 메시지를 그대로 싣지 않는다. 타입만으로도 분류가 된다 —
            //   #9 가 진행 조회에 클래스 이름만 내보낸 것과 같은 판단이다
            log.warn("착수 실패 candidateId={} type={}", candidateId, e.getClass().getSimpleName(), e);
            writer.fail(candidateId, "착수 실패 (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 🔴 <b>최종 게이트</b> — diff 의 경로 집합이 계획을 벗어나면 중단한다.
     *
     * <p>이슈 완료 조건은 「계획에 없는 파일을 <b>건드리면</b> 중단」이다.
     * {@code CodingAgent} 의 조기 차단은 <b>모델 출력</b>만 보므로 부족하다 —
     * 샌드박스에서 도는 <b>포맷터</b>가 워크스페이스를 RW 로 잡고 임의 파일을 고치고,
     * 그것은 모델 출력에 나타나지 않는다.
     */
    private static void assertWithinPlan(Long candidateId, WorkspaceDiff diff,
            ImplementationPlan plan) {
        Set<String> outside = diff.outsideOf(Set.copyOf(plan.paths()));
        if (!outside.isEmpty()) {
            throw new CodingOutOfPlanException(candidateId, outside);
        }
    }

    /**
     * 생성된 내용을 워크스페이스에 쓴다.
     *
     * <p>⚠️ 경로는 {@link SandboxWorkspace} <b>하위로 정규화해 확인</b>한다 —
     * 모델이 {@code ../} 를 돌려주면 워크스페이스 밖에 쓰게 된다 (S-3).
     * {@code SandboxWorkspace} 가 마운트 <b>소스</b>를 검증하는 것과 다른 축이다.
     */
    private static void writeToWorkspace(SandboxWorkspace workspace, List<GeneratedFile> files) {
        for (GeneratedFile file : files) {
            // 🔴 판정은 SandboxWorkspace 가 한다 — 그 타입의 책임이고 #19 도 같은 것을 쓴다.
            //    여기서 손으로 비교하면 두 벌이 되고, 한쪽만 고쳐지는 날이 온다
            Path target = workspace.resolveInside(file.path());
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.content());
            } catch (IOException e) {
                throw new IllegalStateException("생성 파일을 쓰지 못했다", e);
            }
        }
    }

    /** PRD §14 — {@code oss-agent/issue-{번호}-{설명}}. 설명은 우리가 고정한다(모델 입력이 아니다). */
    private static String branchName(Integer issueNumber) {
        return "oss-agent/issue-" + issueNumber + "-fix";
    }

    /**
     * 🔴 실행기가 없으면 <b>전이하지 않는다</b> — 후보를 태우지 않는다.
     *
     * <p>{@code IMPLEMENTING} 에서 나갈 길은 {@code TESTING}·{@code FAILED} 뿐이고
     * <b>{@code FAILED} 는 종단</b>이다. 실행기 없이 전이하면 사람이 버튼 한 번으로
     * <b>후보를 영구히 죽인다.</b>
     *
     * <p>⚠️ {@code UnwiredChangeVerifier} 와 층이 다르다. 그쪽은 <b>코드를 만든 뒤</b>
     * 검증기가 없을 때이고, 그때는 작업이 실제로 있었으므로 {@code FAILED} 가 맞다.
     * 여기는 <b>아무것도 하기 전</b>이라 아무것도 태우지 않는 것이 맞다.
     *
     * <h2>🔴 검증기까지 요구한다 — 「작업만 하고 반드시 실패」를 막는다</h2>
     *
     * <p>코딩까지는 검증기 없이도 돌지만, 그러면 <b>LLM 토큰과 clone 비용을 태우고
     * 반드시 {@code FAILED}</b> 로 끝난다({@link UnwiredChangeVerifier} 가 항상 실패다).
     * 사람이 그것을 눌러 얻는 것이 없다.
     *
     * <p>⚠️ {@code UnwiredChangeVerifier} 는 <b>「코드를 만든 뒤 검증기가 사라진」</b> 경우를
     * 위한 안전망이지, <b>「처음부터 없는」</b> 경우의 정상 동작이 아니다. 후자는 여기서 막는다.
     */
    private boolean executorReady() {
        return workspaces != null
                && codingAgent != null
                && !(verifier instanceof UnwiredChangeVerifier);
    }

    /**
     * 🔴 트랜잭션 안에서 불리는 것을 막는다.
     *
     * <p>이 클래스는 {@code @Transactional} 을 붙이지 않았지만 <b>호출자가 감싸면
     * 규율이 조용히 깨진다.</b> 증상이 「느리다」뿐이라 리뷰에서도 놓치기 쉽다 —
     * 샌드박스 하나가 <b>커넥션을 30분</b> 잡는다.
     */
    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "착수를 트랜잭션 안에서 부를 수 없다 — 샌드박스 실행이 커넥션을 점유한다. "
                            + "호출자의 @Transactional 을 제거하고, 영속화가 필요하면 호출이 끝난 뒤 "
                            + "짧은 트랜잭션으로 분리한다 (architecture.md 규율)");
        }
    }
}
