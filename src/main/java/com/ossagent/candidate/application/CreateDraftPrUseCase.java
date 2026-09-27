package com.ossagent.candidate.application;

import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.CandidateTransitionException;
import com.ossagent.candidate.domain.StatusTransition;
import com.ossagent.issue.application.FindAnalyzableIssuesUseCase;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.pullrequest.domain.BaseBranch;
import com.ossagent.pullrequest.domain.BranchName;
import com.ossagent.pullrequest.domain.DraftPrException;
import com.ossagent.pullrequest.domain.DraftPrPublisher;
import com.ossagent.pullrequest.domain.DraftPrRequest;
import com.ossagent.pullrequest.domain.ForkPublisher;
import com.ossagent.pullrequest.domain.ForkRef;
import com.ossagent.pullrequest.domain.OpenedPullRequest;
import com.ossagent.pullrequest.domain.PrBody;
import com.ossagent.pullrequest.domain.PrBodyMaterials;
import com.ossagent.pullrequest.domain.PrTitle;
import com.ossagent.pullrequest.domain.PublishedBranch;
import com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase;
import com.ossagent.repository.application.PullRequestTarget;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.support.observability.GateOutcome;
import com.ossagent.support.observability.PipelineMetrics;
import com.ossagent.support.observability.SafetyClause;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 🔴 <b>사람의 승인 지점 ③ — Draft PR 생성</b> (#23 · S-6).
 *
 * <p>PRD §20 이 정한 승인 지점 셋 중 <b>마지막</b>이고, <b>여기서 자동화가 끝난다.</b>
 * 이 호출 뒤 후보는 종단 {@code PR_CREATED} 이고 나가는 전이가 없다.
 *
 * <h2>🔴 트랜잭션을 세 토막으로 나눈다</h2>
 *
 * <pre>
 * ① 읽기 tx   후보·변경분을 값으로 꺼낸다            (CandidatePrWriter.load)
 * ② 대외      정책 재확인 · 템플릿 · Fork · PR 생성   ← 트랜잭션 없음
 * ③ 쓰기 tx   PR 행 + 전이                          (CandidatePrWriter.attachPullRequest)
 * </pre>
 *
 * <p>이 메서드에 {@code @Transactional} 이 <b>없는 것이 설계</b>다. 달면 ②가 통째로
 * 트랜잭션 안에 들어가 GitHub 응답을 기다리는 내내 DB 커넥션이 잡힌다.
 * 쪼갠 조각이 별도 빈({@code CandidatePrWriter})에 있는 이유는 self-invocation 이
 * 프록시를 타지 않기 때문이다.
 *
 * <h2>🔴 ②가 끝난 뒤에만 ③이 돈다 — FR-9</h2>
 *
 * <p>{@code PR_CREATED} 는 종단이라 되돌릴 수 없다. 순서를 뒤집으면 「PR 이 있다고
 * 기록됐는데 없는」 후보가 <b>빠져나올 수 없는 상태로</b> 남는다.
 *
 * <p>⚠️ 반대 방향의 사고(PR 은 만들어졌는데 ③이 실패)는 <b>막지 않고 복구한다</b> —
 * PR 을 닫아 되돌리지 않는다(알림은 회수되지 않는다). 다음 호출에서
 * {@link DraftPrPublisher#findOpen} 이 같은 PR 을 찾아 붙인다.
 */
@Service
public class CreateDraftPrUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateDraftPrUseCase.class);

    private final CandidatePrWriter writer;
    private final FindAnalyzableIssuesUseCase issues;
    private final AnalyzeRepositoryPolicyUseCase policies;
    private final ForkPublisher forkPublisher;
    private final DraftPrPublisher draftPrs;
    private final PipelineMetrics metrics;

    public CreateDraftPrUseCase(CandidatePrWriter writer, FindAnalyzableIssuesUseCase issues,
            AnalyzeRepositoryPolicyUseCase policies, ForkPublisher forkPublisher,
            DraftPrPublisher draftPrs, PipelineMetrics metrics) {
        this.writer = writer;
        this.issues = issues;
        this.policies = policies;
        this.forkPublisher = forkPublisher;
        this.draftPrs = draftPrs;
        this.metrics = metrics;
    }

    /**
     * {@code READY_FOR_PR → PR_CREATED} — 🔴 <b>사람이 누른다.</b>
     *
     * @throws CandidateTransitionException {@code READY_FOR_PR} 이 아니다 → 409
     * @throws com.ossagent.repository.domain.ContributionNotAllowedException
     *         AI 기여가 허용이 아니다 → 403 (S-5)
     * @throws DraftPrException PR 을 만들지 못했다. <b>후보는 전이하지 않은 채 남는다</b>
     */
    public Result create(Long candidateId) {
        if (candidateId == null) {
            throw new IllegalArgumentException("후보 식별자는 필수입니다");
        }

        // ── ① 읽기 ───────────────────────────────────────────────────
        CandidatePrWriter.PrSnapshot snapshot = writer.load(candidateId);
        assertReadyForPr(candidateId, snapshot);

        AnalyzableIssue issue = issues.findOne(snapshot.issueId())
                .orElseThrow(() -> new DraftPrException(
                        "후보의 이슈를 찾지 못했습니다 candidateId=" + candidateId
                                + " issueId=" + snapshot.issueId()));

        // ── ② 대외 — 트랜잭션 밖 ──────────────────────────────────────
        // 🔴 PR 생성 직전에 정책을 다시 본다 — S-5 · FR-13.
        //    #18 이 재시도 루프에서 재확인을 생략한 근거는 「바퀴마다 3배」라는 비용이었는데,
        //    여기는 후보당 정확히 1회라 그 논거가 서지 않는다. 그리고 여기서 틀리면
        //    「금지된 저장소에 Draft PR 이 나간다」— 되돌릴 수 없는 쪽이다.
        //    ⚠ 재분석은 돌리지 않는다. 저장된 판정을 다시 읽을 뿐이다(#68 의 강등이 그 사이에
        //      일어났다면 여기서 걸린다).
        policies.assertContributionAllowed(issue.repositoryId());

        ContributionConstraints constraints = policies.constraintsOf(issue.repositoryId());
        PullRequestTarget target = policies.findPullRequestTarget(issue.repositoryId());

        PublishedBranch head = resolveHead(candidateId, snapshot, target);

        // 🔴 이미 열린 PR 이 있으면 두 번째를 만들지 않는다 — S-2 · FR-12
        Optional<OpenedPullRequest> existing = draftPrs.findOpen(target.upstream(), head);
        OpenedPullRequest opened = existing.orElseGet(() -> draftPrs.openDraft(
                buildRequest(issue, snapshot, constraints, target, head)));

        if (existing.isPresent()) {
            log.warn("이미 열린 PR 을 붙인다 — 새로 만들지 않는다 candidateId={} prNumber={}",
                    candidateId, opened.number());
        }

        // ── ③ 쓰기 ───────────────────────────────────────────────────
        StatusTransition transition = writer.attachPullRequest(candidateId,
                opened.forkUrl(), head.branchName().value(), opened.number(), opened.url());

        metrics.safetyGate(SafetyClause.S2, GateOutcome.PASSED, null);
        return new Result(candidateId, opened, transition, existing.isPresent());
    }

    /**
     * 🔴 대외 호출을 태우기 전에 상태를 본다.
     *
     * <p>이것이 <b>권위 있는 판정은 아니다</b> — 그것은 ③의 엔티티 전이다. 여기서 보는
     * 이유는 「잘못 눌렀는데 GitHub 호출 7번을 태우고 나서 409」를 막기 위해서다.
     *
     * <p>⚠️ 그래서 <b>③의 전이를 생략하지 않는다.</b> 두 곳이 같은 것을 본다고 한쪽을
     * 지우면, 지운 쪽이 「먼저 보는 쪽」이 아니라 「유일하게 보는 쪽」이었음이 나중에 드러난다.
     */
    private void assertReadyForPr(Long candidateId, CandidatePrWriter.PrSnapshot snapshot) {
        if (snapshot.status() == CandidateStatus.READY_FOR_PR) {
            return;
        }
        // 🔴 거부는 지금 남긴다 — 예외가 올라가면 기록할 기회가 없다 (logging.md)
        log.warn("승인 게이트 거부 gate=PR 생성 candidateId={} status={} reason=READY_FOR_PR 이 아니다",
                candidateId, snapshot.status());
        // ⚠ reason 태그는 enum 아니면 "NONE" 이다 — 후보 상태 문자열을 태그로 흘리지 않는다.
        //   MetricTagRuleTest 가 그것을 막고, 막는 것이 옳다(카디널리티가 태그로 새면 미터가 폭발한다)
        metrics.safetyGate(SafetyClause.S2, GateOutcome.BLOCKED, null);
        throw new CandidateTransitionException(
                "PR 을 만들 수 없는 상태입니다 candidateId=" + candidateId
                        + " status=" + snapshot.status()
                        + " — READY_FOR_PR 인 후보만 PR 을 열 수 있습니다 (S-6)");
    }

    /**
     * Fork 브랜치를 복원한다.
     *
     * <p>🔴 <b>Fork 좌표를 조립하지 않는다.</b> 같은 이름의 저장소가 이미 있으면 GitHub 은
     * fork 를 {@code {name}-1} 로 만들고, 조립하면 <b>영원히 못 찾는다</b> —
     * {@code GitHubWriteClient.createFork} javadoc 이 적어 둔 함정이다.
     * {@code ensureFork} 가 그 판정을 이미 갖고 있으므로 그것을 쓴다(「같은 이름의 무관한
     * 저장소」도 그쪽이 가른다).
     *
     * <p>⚠️ 브랜치 이름이 {@code BranchName} 형식이 아니면 여기서 막힌다 — <b>우리가 만들지
     * 않은 브랜치로 PR 을 열지 않는다</b>는 뜻이라 그것이 옳다.
     */
    private PublishedBranch resolveHead(Long candidateId, CandidatePrWriter.PrSnapshot snapshot,
            PullRequestTarget target) {

        if (snapshot.branchName() == null || snapshot.commitSha() == null) {
            throw new DraftPrException(
                    "올라간 브랜치를 찾지 못했습니다 candidateId=" + candidateId
                            + " — Fork 에 push 한 기록(GeneratedChange)이 없습니다");
        }
        ForkRef fork = forkPublisher.ensureFork(target.upstream());
        return new PublishedBranch(fork, new BranchName(snapshot.branchName()),
                snapshot.commitSha(), false);
    }

    private DraftPrRequest buildRequest(AnalyzableIssue issue,
            CandidatePrWriter.PrSnapshot snapshot, ContributionConstraints constraints,
            PullRequestTarget target, PublishedBranch head) {

        PrBodyMaterials materials = new PrBodyMaterials(
                target.template(),
                changeSummary(issue),
                issueReference(issue, constraints),
                verificationSection(snapshot.scrubbedVerification(), constraints),
                snapshot.scrubbedReview());

        return new DraftPrRequest(
                target.upstream(),
                head,
                new BaseBranch(target.defaultBranch()),
                PrTitle.forIssue(issue.githubIssueNumber(), issue.title()),
                PrBody.compose(materials));
    }

    private static String changeSummary(AnalyzableIssue issue) {
        // ⚠ 이슈 본문을 그대로 옮기지 않는다 — 메인테이너는 자기 이슈를 이미 읽었고,
        //   우리가 옮긴 사본이 원본과 어긋나면 그쪽이 혼란스럽다
        return "Addresses issue #%d (%s)."
                .formatted(issue.githubIssueNumber(), issue.url() == null ? "" : issue.url());
    }

    /**
     * 🔴 규약이 이슈 참조를 요구하면 <b>반드시</b> 넣는다 — S-5.
     *
     * <p>⚠️ 자동 닫기 키워드({@code Fixes}·{@code Closes})를 붙이지 않는다. 언제 닫을지는
     * 메인테이너가 정하고, 우리 판단으로 닫아 두면 그쪽 트리아지를 침범한다 —
     * #22 가 커밋 메시지에서 같은 판단을 했다.
     */
    private static String issueReference(AnalyzableIssue issue, ContributionConstraints constraints) {
        // ⚠ 플래그가 참이든 아니든 <b>항상</b> 넣는다. 우리는 이슈 번호를 항상 알고 있으므로
        //   「요구되는데 넣지 못하는」 경우가 존재하지 않는다 — 그래서 분기가 없는 것이 정직하다.
        //   🔴 분기를 만들어 두면 「요구되지 않으면 뺀다」로 읽히고, 그 순간 규약이
        //      issueReferenceRequired 를 늦게 켠 저장소에 참조 없는 PR 이 나간다
        if (constraints.issueReferenceRequired() && issue.githubIssueNumber() == null) {
            throw new DraftPrException(
                    "규약이 이슈 참조를 요구하는데 이슈 번호를 모릅니다 issueId=" + issue.id());
        }
        return "See #%d.".formatted(issue.githubIssueNumber());
    }

    /**
     * 🔴 <b>판정이 아니라 실행 기록으로 적는다</b> — Q-4 가 아직 열려 있다.
     *
     * <p>실행 단계가 {@code network=none} 이라 <b>정상 코드인데 테스트가 실패</b>할 수 있고
     * (Testcontainers · 임베디드 브로커 · 외부 엔드포인트), Q-4 가 「지금은 그것을 코드가
     * 틀렸다로 보고하며 <b>그 판정이 옳지 않다</b>」고 명시해 뒀다.
     *
     * <p>그 미결을 <b>남의 저장소에 「테스트 통과」로 내보내지 않는다.</b> 무엇을 어떤
     * 환경에서 돌렸는지만 적고, 해석은 읽는 사람에게 맡긴다.
     */
    private static String verificationSection(String testResult, ContributionConstraints constraints) {
        if (testResult == null || testResult.isBlank()) {
            return null;
        }
        String command = constraints.hasTestCommand() ? constraints.testCommand() : "(unknown)";
        return """
                Executed in an offline sandbox container (no network access), so tests that require \
                network or containers may not have run. Read this as a record of what was executed, \
                not as a pass/fail verdict.

                Command: `%s`

                ```
                %s
                ```""".formatted(command, testResult);
    }

    /**
     * 게이트 결과.
     *
     * @param reusedExisting 이미 열려 있던 PR 을 붙였는가. {@code true} 면 우리가 새로 만들지
     *                       않았다는 뜻이다 — 사람이 그 사실을 알아야 한다
     */
    public record Result(Long candidateId, OpenedPullRequest pullRequest,
                         StatusTransition transition, boolean reusedExisting) {
    }
}
