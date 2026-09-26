package com.ossagent.repository.application;

import com.ossagent.candidate.application.AnalysisResult;
import com.ossagent.candidate.application.AnalyzeIssuesUseCase;
import com.ossagent.issue.application.FilterIssuesUseCase;
import com.ossagent.issue.application.FilterResult;
import com.ossagent.issue.application.ScanIssuesUseCase;
import com.ossagent.issue.application.ScanResult;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.support.github.GitHubRateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 저장소 하나의 파이프라인을 끝까지 돌린다 — #14.
 *
 * <pre>
 *   정책 보장(#7)  →  수집(#8)  →  필터(#9)  →  분석(#11)
 * </pre>
 *
 * <h2>🔴 S-6 — 자동 경로는 {@code ANALYZED} 에서 멈춘다</h2>
 *
 * <p>이 클래스가 만들어지기 전까지 S-6 의 「스케줄러가 끝까지 자동으로 흘려보내지 않는다」는
 * <b>스케줄러가 없어서</b> 지켜지고 있었다. 이제 지키는 주체가 이 코드다.
 *
 * <p><b>부르지 않는 것</b> — {@code selectByHuman} · {@code startImplementing} ·
 * Fork push · Draft PR 생성. 생성자 의존이 <b>네 UseCase 로 고정</b>돼 있는 것이 그 준수다.
 * 다섯 번째 의존을 주입하려는 변경은 <b>그 자체로 리뷰 대상</b>이다 —
 * #8 이 {@code ScanIssuesUseCase} javadoc 에서 「{@code candidate} 도메인을 import 하지
 * 않는 것이 그 준수다」로 한 방식과 같다.
 *
 * <p>⚠️ {@code scan.schedule.enabled} 는 <b>S-6 방어가 아니다.</b> 그것은 비용·레이트리밋
 * 방어이고, S-6 은 스케줄러를 켜든 끄든 성립한다. 섞어 두면 「운영에서 어차피 켤 거니
 * 의미 없다」로 둘 다 약해진다.
 *
 * <h2>🔴 트랜잭션을 걸지 않는다</h2>
 *
 * <p>네 단계 전부가 GitHub·LLM 을 부른다. 감싸면 커넥션이 분 단위로 잡힌다.
 * 네 UseCase 가 각자 짧은 트랜잭션을 열고, 이 클래스는 {@link #assertNoTransaction()} 으로
 * 호출자가 감싸는 것을 거부한다.
 *
 * <h2>실패는 삼키되 버리지 않는다 (NFR-4)</h2>
 *
 * <p>수집은 됐는데 분석이 죽었다고 수집분을 0으로 보고하면, 사람이 「아무 일도 없었다」로
 * 읽고 같은 구간을 다시 읽히게 된다. 앞 단계 성과는 결과에 실어 보낸다.
 */
@Service
public class ScanPipelineUseCase {

    private static final Logger log = LoggerFactory.getLogger(ScanPipelineUseCase.class);

    private final AnalyzeRepositoryPolicyUseCase repositoryPolicy;
    private final ScanIssuesUseCase scanIssues;
    private final FilterIssuesUseCase filterIssues;
    private final AnalyzeIssuesUseCase analyzeIssues;

    public ScanPipelineUseCase(AnalyzeRepositoryPolicyUseCase repositoryPolicy,
            ScanIssuesUseCase scanIssues,
            FilterIssuesUseCase filterIssues,
            AnalyzeIssuesUseCase analyzeIssues) {
        this.repositoryPolicy = repositoryPolicy;
        this.scanIssues = scanIssues;
        this.filterIssues = filterIssues;
        this.analyzeIssues = analyzeIssues;
    }

    /**
     * @throws ScanStageFailedException 어느 단계가 실패했는지를 실어 던진다.
     *         호출자가 진행 조회에 기록한다 — 🔴 예외 <b>원문</b>은 싣지 않는다 (S-4)
     */
    public ScanPipelineResult run(Long repositoryId) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수다");
        }
        assertNoTransaction();

        // ── 0단계. 정책 보장 (FR-0) ──────────────────────────────────
        ScanTarget target;
        try {
            target = repositoryPolicy.analyzeIfAbsent(repositoryId);
        } catch (GitHubRateLimitException e) {
            // 🔴 지연이지 실패가 아니다. analyze() 의 catch 는 LlmTransientException 뿐이라
            //    fetchMetadata 에서 난 리밋은 맨몸으로 여기까지 올라온다
            log.info("규약 분석이 레이트리밋에 걸렸다 repositoryId={} — 다음 주기가 이어받는다",
                    repositoryId);
            return ScanPipelineResult.skipped(ScanTarget.SkipReason.RATE_LIMITED);
        }

        if (!target.contributionAllowed()) {
            // 🔴 수집 전에 끊는다 — 반영할 수 없는 판정에 레이트리밋·토큰을 쓰지 않는다.
            //    ⚠ 게이트는 분석 단계에 그대로 남아 있다. 이것은 조기 차단일 뿐이다
            log.info("수집을 건너뛴다 repositoryId={} reason={} transient={}",
                    repositoryId, target.skipReason(), target.isTransientSkip());
            return ScanPipelineResult.skipped(target.skipReason());
        }

        // ── 1단계. 수집 ─────────────────────────────────────────────
        ScanResult scan;
        try {
            scan = scanIssues.scan(repositoryId, target.coordinates());
        } catch (RuntimeException e) {
            throw ScanStageFailedException.at(ScanExecutionState.Stage.SCAN, repositoryId, e);
        }

        // ── 2단계. 필터 ─────────────────────────────────────────────
        FilterResult filter;
        try {
            filter = filterIssues.filter(repositoryId);
        } catch (RuntimeException e) {
            // 수집분은 이미 DB 에 있다. 버리지 않는다 (NFR-4)
            throw ScanStageFailedException.at(ScanExecutionState.Stage.FILTER, repositoryId, e,
                    ScanPipelineResult.partial(scan, null));
        }

        // ── 3단계. 분석 ─────────────────────────────────────────────
        AnalysisResult analysis;
        try {
            analysis = analyzeIssues.analyze(repositoryId);
        } catch (ContributionNotAllowedException e) {
            // 🔴 실패가 아니다 — S-5 게이트가 정상 작동한 것이다.
            //    0단계에서 걸렀어야 하지만, 그 사이 사람이 정책을 바꿨을 수 있다
            log.info("분석 게이트가 막았다 repositoryId={} reason={} — 수집·필터 결과는 남는다",
                    repositoryId, e.reason());
            return ScanPipelineResult.partial(scan, filter);
        } catch (RuntimeException e) {
            throw ScanStageFailedException.at(ScanExecutionState.Stage.ANALYZE, repositoryId, e,
                    ScanPipelineResult.partial(scan, filter));
        }

        ScanPipelineResult result = ScanPipelineResult.of(scan, filter, analysis);
        // 이슈 본문·판정 내용은 남기지 않는다 — 수치와 우리 어휘만 (logging.md · S-4)
        log.info("스캔 파이프라인 완료 repositoryId={} {}", repositoryId, result);
        return result;
    }

    /**
     * 🔴 대외 호출이 트랜잭션 안에 들어가는 것을 막는다.
     *
     * <p>네 단계가 전부 대외 호출이다. 호출자가 감싸면 커넥션이 분 단위로 잡히는데
     * <b>증상이 「느리다」뿐</b>이라 리뷰에서 놓치기 쉽다.
     */
    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "스캔 파이프라인을 트랜잭션 안에서 부를 수 없다 — GitHub·LLM 호출이 "
                            + "커넥션을 점유한다. 호출자의 @Transactional 을 제거한다");
        }
    }
}
