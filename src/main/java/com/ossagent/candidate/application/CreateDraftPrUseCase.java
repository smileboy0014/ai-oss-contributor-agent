package com.ossagent.candidate.application;

import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.TargetWorkspaceSource;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.CandidateTransitionException;
import com.ossagent.candidate.domain.StatusTransition;
import com.ossagent.issue.application.FindAnalyzableIssuesUseCase;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.pullrequest.domain.BaseBranch;
import com.ossagent.pullrequest.domain.BranchName;
import com.ossagent.pullrequest.domain.CommitMessage;
import com.ossagent.pullrequest.domain.DraftPrException;
import com.ossagent.pullrequest.domain.DraftPrPublisher;
import com.ossagent.pullrequest.domain.DraftPrRequest;
import com.ossagent.pullrequest.domain.FileChange;
import com.ossagent.pullrequest.domain.ForkPublisher;
import com.ossagent.pullrequest.domain.ForkRef;
import com.ossagent.pullrequest.domain.OpenedPullRequest;
import com.ossagent.pullrequest.domain.PrBody;
import com.ossagent.pullrequest.domain.PrBodyMaterials;
import com.ossagent.pullrequest.domain.PrTitle;
import com.ossagent.pullrequest.domain.PublishRequest;
import com.ossagent.pullrequest.domain.PublishedBranch;
import com.ossagent.pullrequest.domain.SyncedFork;
import com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase;
import com.ossagent.repository.application.PullRequestTarget;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.observability.GateOutcome;
import com.ossagent.support.observability.PipelineMetrics;
import com.ossagent.support.observability.PipelineStage;
import com.ossagent.support.observability.SafetyClause;
import com.ossagent.support.observability.StageOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * ① 읽기 tx   후보·변경분(diff)을 값으로 꺼낸다                 (CandidatePrWriter.load)
 * ② 대외      정책 재확인 · 템플릿 · Fork 확보 · 동기화          ← 트랜잭션 없음
 *             · upstream 재clone + diff 적용 · Fork push          (S-1 — 여기가 유일한 쓰기)
 * ②' 쓰기 tx  push 된 커밋 sha 기록                             (CandidatePrWriter.recordPublished)
 * ②  대외      Draft PR 생성                                    (S-2)
 * ③ 쓰기 tx   PR 행 + 전이                                      (CandidatePrWriter.attachPullRequest)
 * </pre>
 *
 * <h2>🔴 Fork push 는 이 게이트 뒤에서 일어난다 — S-1 · S-6</h2>
 *
 * <p>#22 가 만든 {@code ForkPublisher.publish} 에는 오랫동안 <b>호출자가 없었다.</b> PLAN-22 는
 * 「배선은 #23 이 게이트 뒤에 놓는다」고, PLAN-23 은 「#22 가 push 를 끝내 sha 까지 준다」고 서로에게
 * 넘겨, {@code GeneratedChange.commitSha} 가 영영 {@code null} 이었고 이 게이트는 항상
 * 「push 한 기록이 없다」로 실패했다. 지금은 여기서 push 하고 그 sha 를 기록한 뒤 PR 을 연다.
 *
 * <p>Fork 에 올릴 파일은 <b>착수 때의 워크스페이스를 다시 읽지 않는다.</b> 그 디렉토리는 같은
 * 저장소의 다른 후보가 착수하면 통째로 비워지고 재기동 뒤에는 없을 수도 있다. 정본은 DB 의
 * {@code GeneratedChange.diff} 이고, upstream 을 새로 받아 그 diff 를 입힌다 — 그 사이 upstream 이
 * 움직여 맞지 않으면 여기서 끊기고 사람이 다시 착수한다.
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
    private final TargetWorkspaceSource workspaces;
    private final PipelineMetrics metrics;
    private final Clock clock;

    /**
     * @param workspaces 🔴 {@link ObjectProvider} — 대역 프로필에서 빠진다({@code @ExternalAdapter}).
     *                   없으면 push 할 파일을 만들 수 없으므로 게이트 통과 뒤 {@link DraftPrException} 이다.
     *                   {@code ImplementCandidateUseCase} 가 같은 이유로 같은 모양을 쓴다
     */
    public CreateDraftPrUseCase(CandidatePrWriter writer, FindAnalyzableIssuesUseCase issues,
            AnalyzeRepositoryPolicyUseCase policies, ForkPublisher forkPublisher,
            DraftPrPublisher draftPrs, ObjectProvider<TargetWorkspaceSource> workspaces,
            PipelineMetrics metrics, Clock clock) {
        this.writer = writer;
        this.issues = issues;
        this.policies = policies;
        this.forkPublisher = forkPublisher;
        this.draftPrs = draftPrs;
        this.workspaces = workspaces.getIfAvailable();
        this.metrics = metrics;
        this.clock = clock;
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

        // 🔴 이슈 번호가 없으면 제목도 참조도 만들 수 없다 — S-5(규약이 이슈 참조를 요구할 수 있다).
        //    ⚠ 「실무상 NOT NULL 이니 괜찮다」로 두면 PrTitle.forIssue 의 int 언박싱에서
        //      NullPointerException 이 나고, 그것은 「왜 PR 이 안 만들어지는지」를 말해주지 않는다.
        //      AnalyzableIssue 는 id·repositoryId 만 검증하므로 여기서 본다
        if (issue.githubIssueNumber() == null) {
            throw new DraftPrException(
                    "이슈 번호를 알 수 없어 PR 을 만들 수 없습니다 candidateId=" + candidateId
                            + " issueId=" + snapshot.issueId());
        }

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

        Instant started = clock.instant();
        try {
            // 🔴 Fork 에 올린다 — S-1 의 유일한 쓰기 경로. PR 보다 먼저다(head 가 있어야 PR 이 있다)
            PublishedBranch head = publishToFork(candidateId, issue, snapshot, constraints, target);
            // ②' push 는 성공했다 — PR 생성이 실패해도 이 기록으로 다음 호출이 「갱신」으로 이어 간다
            writer.recordPublished(candidateId, head.commitSha());

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
            metrics.pipelineStage(PipelineStage.PULL_REQUEST, StageOutcome.SUCCEEDED,
                    Duration.between(started, clock.instant()));
            return new Result(candidateId, opened, transition, existing.isPresent());
        } catch (RuntimeException e) {
            metrics.pipelineStage(PipelineStage.PULL_REQUEST, StageOutcome.FAILED,
                    Duration.between(started, clock.instant()));
            throw e;
        }
    }

    /**
     * 동기화 → 재clone + diff 적용 → push. <b>S-1 의 실행 지점</b>이고 이 클래스에서 유일하다.
     *
     * <p>순서가 방어다 — {@link PublishRequest} 가 {@link SyncedFork} 를 인자로 요구하므로
     * 동기화 결과를 보지 않고 push 하는 코드가 컴파일되지 않는다. {@code CONFLICT}·{@code UNMERGEABLE}
     * 면 여기서 끊는다: 그 위에 커밋을 쌓으면 PR 이 충돌 상태로 열려 메인테이너 큐를 오염시킨다 (S-2).
     *
     * <p>{@code allowUpdate} 는 「우리가 전에 올린 브랜치인가」다 — {@code commitSha} 가 기록돼 있으면
     * 앞 호출이 push 까지 하고 PR 생성에서 죽은 것이라 갱신이고, 없으면 첫 push 라 생성이다.
     * 생성으로 보냈는데 브랜치가 이미 있으면(우리 기록에 없는 브랜치) 어댑터가 422 로 끊는다 —
     * 남의 것일 수 있는 브랜치를 덮어쓰지 않는다.
     */
    private PublishedBranch publishToFork(Long candidateId, AnalyzableIssue issue,
            CandidatePrWriter.PrSnapshot snapshot, ContributionConstraints constraints,
            PullRequestTarget target) {

        if (snapshot.branchName() == null || snapshot.unifiedDiff() == null
                || snapshot.unifiedDiff().isBlank()) {
            throw new DraftPrException(
                    "검증을 통과한 변경분을 찾지 못했습니다 candidateId=" + candidateId
                            + " — GeneratedChange 행(브랜치·diff)이 없습니다");
        }
        if (workspaces == null) {
            throw new DraftPrException(
                    "워크스페이스 소스가 배선되지 않아 Fork 에 올릴 파일을 만들 수 없습니다 candidateId="
                            + candidateId);
        }
        // ⚠️ 우리 형식(PRD §14)이 아니면 여기서 막힌다 — 우리가 만들지 않은 브랜치로 PR 을 열지 않는다
        BranchName branch = new BranchName(snapshot.branchName());
        BaseBranch base = new BaseBranch(target.defaultBranch());

        ForkRef fork = forkPublisher.ensureFork(target.upstream());
        SyncedFork synced = forkPublisher.syncWithUpstream(fork, base);
        if (!synced.outcome().isAligned()) {
            log.warn("Fork 동기화 실패 — push 하지 않는다 candidateId={} fork={} outcome={}",
                    candidateId, fork.fullName(), synced.outcome());
            throw new DraftPrException(
                    "Fork 의 기준 브랜치를 upstream 과 맞추지 못했습니다 (%s) — Fork 를 정리한 뒤 다시 요청하세요"
                            .formatted(synced.outcome()));
        }

        List<FileChange> changes = materialize(target.upstream(), branch, snapshot.unifiedDiff());
        CommitMessage message = CommitMessage.from(
                PrTitle.forIssue(issue.githubIssueNumber(), issue.title()).value(),
                changeSummary(issue), constraints, issue.githubIssueNumber(),
                forkPublisher.commitIdentity().orElse(null));
        boolean allowUpdate = snapshot.commitSha() != null;

        PublishedBranch head = forkPublisher.publish(
                new PublishRequest(synced, base, branch, message, changes, allowUpdate));
        metrics.safetyGate(SafetyClause.S1, GateOutcome.PASSED, null);
        log.info("Fork push 완료 candidateId={} fork={} branch={} files={} updated={}",
                candidateId, fork.fullName(), branch, changes.size(), head.updated());
        return head;
    }

    /**
     * upstream 을 새로 받아 저장된 diff 를 입히고, 바뀐 파일을 값으로 읽는다.
     *
     * <p>🔴 <b>텍스트만 올린다.</b> UTF-8 로 읽히지 않는 파일이 diff 에 섞여 있으면 끊는다 —
     * 깨진 바이트를 문자열로 올리면 Fork 의 파일이 조용히 손상된다. {@code FileChange} 가
     * 바이너리를 표현할 수 없다고 적어 둔 그 제약이다.
     */
    private List<FileChange> materialize(RepositoryCoordinates upstream, BranchName branch,
            String unifiedDiff) {
        SandboxWorkspace workspace = workspaces.fetch(upstream, branch.value());
        // 🔴 바뀐 경로는 apply 가 돌려준다. 적용 뒤 diff() 를 다시 부르면 JGit 이 인덱스까지
        //    갱신한 탓에 항상 비어, 「입혔는데 바뀐 것이 없다」로 게이트가 매번 죽었다 (#95)
        Set<String> applied = workspaces.apply(workspace, unifiedDiff);
        if (applied.isEmpty()) {
            throw new DraftPrException("변경분을 입혔는데 바뀐 파일이 없습니다 — diff 가 비어 있거나 이미 반영됐습니다");
        }

        List<FileChange> changes = new ArrayList<>();
        for (String path : new TreeSet<>(applied)) {
            // 🔴 경로 판정은 SandboxWorkspace 가 한다 — ../ 와 심볼릭 링크를 거른다 (S-3)
            Path file = workspace.resolveInside(path);
            if (!Files.exists(file)) {
                changes.add(FileChange.deleted(path));
                continue;
            }
            changes.add(new FileChange(path, readUtf8(file, path), false, Files.isExecutable(file)));
        }
        return changes;
    }

    private static String readUtf8(Path file, String path) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // ⚠ 파일 내용을 메시지에 싣지 않는다 — 경로만 (S-4)
            throw new DraftPrException("텍스트로 읽을 수 없는 파일이 변경분에 있습니다: " + path, e);
        }
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

    private DraftPrRequest buildRequest(AnalyzableIssue issue,
            CandidatePrWriter.PrSnapshot snapshot, ContributionConstraints constraints,
            PullRequestTarget target, PublishedBranch head) {

        PrBodyMaterials materials = new PrBodyMaterials(
                target.template(),
                changeSummary(issue),
                issueReference(issue),
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
    private static String issueReference(AnalyzableIssue issue) {
        // ⚠ 규약의 issueReferenceRequired 와 무관하게 **항상** 넣는다. 이슈 번호가 없으면
        //   위에서 이미 끊겼으므로 「요구되는데 넣지 못하는」 경우가 존재하지 않는다 —
        //   그래서 분기가 없는 것이 정직하다.
        //   🔴 분기를 만들어 두면 「요구되지 않으면 뺀다」로 읽히고, 그 순간 규약이
        //      issueReferenceRequired 를 늦게 켠 저장소에 참조 없는 PR 이 나간다
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
