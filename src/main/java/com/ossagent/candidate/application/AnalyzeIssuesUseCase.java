package com.ossagent.candidate.application;

import com.ossagent.agent.domain.LlmException;
import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.AnalysisRejectedException;
import com.ossagent.candidate.domain.IssueAnalysis;
import com.ossagent.candidate.domain.IssueAnalyst;
import com.ossagent.issue.application.FindAnalyzableIssuesUseCase;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.repository.application.AnalyzeRepositoryPolicyUseCase;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 필터를 통과한 이슈를 LLM 으로 분석해 기여 후보로 만든다 — PRD §11 · #11.
 *
 * <h2>🔴 이 클래스에 {@code @Transactional} 이 없는 것이 설계다</h2>
 *
 * <p>LLM 호출이 이 메서드 안에서 일어난다. 트랜잭션을 걸면 배치 내내 커넥션을 점유한다.
 * 쓰기는 {@link CandidateAnalysisWriter} 의 짧은 트랜잭션 둘로 나뉜다.
 * 실수로 밖에서 감싸는 것을 {@link #assertNoTransaction()} 이 막는다.
 *
 * <h2>🔴 저장소 스코프다 — S-5 게이트가 그것을 요구한다</h2>
 *
 * <p>{@code RepositoryPolicy} 는 저장소당 1건이다. 전역 배치로 만들면 게이트를 배치 앞에서
 * 한 번 부를 수 없고, 한 저장소가 보류라고 다른 저장소 이슈까지 멈추는 모순이 생긴다.
 *
 * <h2>흐름</h2>
 *
 * <pre>
 *   assertContributionAllowed(repositoryId)     ← 🔴 S-5. 보류·금지면 여기서 끝난다
 *   loop (max-batches-per-run)
 *     ├ 분석 대상 이슈 한 페이지  (PASSED · UNDECIDED · 우선순위 순)
 *     ├ 이미 후보가 있는 것 제외   ← 최적화. 멱등의 정본은 UNIQUE(issue_id)
 *     └ per issue
 *         ├ TX1  후보 생성 + ANALYZING
 *         ├ ───  LLM 호출 (트랜잭션 밖)
 *         └ TX2  ANALYZED (+ REJECTED) 또는 FAILED
 * </pre>
 *
 * <h2>🔴 S-6 — {@code SELECTED} 로 가지 않는다</h2>
 *
 * <p>이 클래스가 만드는 상태는 {@code ANALYZED}·{@code REJECTED}·{@code FAILED} 뿐이다.
 * {@code selectByHuman} 을 부르지 않는다. 스케줄러(#14)가 이 UseCase 를 돌려도
 * 사람의 선택 지점(#24)은 그대로 남는다.
 */
@Service
public class AnalyzeIssuesUseCase {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeIssuesUseCase.class);

    private final FindAnalyzableIssuesUseCase analyzableIssues;
    private final AnalyzeRepositoryPolicyUseCase repositoryPolicy;
    private final ContributionCandidateRepository candidates;
    private final CandidateAnalysisWriter writer;
    private final IssueAnalyst analyst;
    private final IssueAnalysisProperties properties;

    public AnalyzeIssuesUseCase(FindAnalyzableIssuesUseCase analyzableIssues,
            AnalyzeRepositoryPolicyUseCase repositoryPolicy,
            ContributionCandidateRepository candidates,
            CandidateAnalysisWriter writer,
            IssueAnalyst analyst,
            IssueAnalysisProperties properties) {
        this.analyzableIssues = analyzableIssues;
        this.repositoryPolicy = repositoryPolicy;
        this.candidates = candidates;
        this.writer = writer;
        this.analyst = analyst;
        this.properties = properties;
    }

    /**
     * @throws com.ossagent.repository.domain.ContributionNotAllowedException
     *         🔴 규약을 읽지 못했거나(보류) AI 기여가 금지된 저장소 — S-5
     */
    public AnalysisResult analyze(Long repositoryId) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("저장소 식별자는 필수다");
        }
        assertNoTransaction();

        // 🔴 S-5 — 배치를 시작하기 전에 판정한다. 보류(UNDETERMINED)도 금지와 같이 막는다.
        //    「아직 판정 안 됨이니 일단 분석은 해 두자」로 풀면 이 게이트가 무의미해진다
        repositoryPolicy.assertContributionAllowed(repositoryId);

        Counter counter = new Counter();
        Short afterPriority = null;
        Long afterId = null;
        boolean hasMore = false;

        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            List<AnalyzableIssue> page = analyzableIssues.findAnalyzable(
                    repositoryId, afterPriority, afterId, properties.batchSize());
            if (page.isEmpty()) {
                hasMore = false;
                break;
            }

            Set<Long> alreadyCandidates = candidates.findExistingIssueIds(
                    page.stream().map(AnalyzableIssue::id).toList());

            for (AnalyzableIssue issue : page) {
                if (alreadyCandidates.contains(issue.id())) {
                    counter.skipped++;
                    continue;
                }
                analyzeOne(issue, counter);
            }

            AnalyzableIssue last = page.getLast();
            afterPriority = last.priorityKey();
            afterId = last.id();
            hasMore = page.size() == properties.batchSize();
            if (!hasMore) {
                break;
            }
        }

        AnalysisResult result = new AnalysisResult(
                counter.analyzed, counter.rejected, counter.failed, counter.skipped, hasMore);
        // 이슈 본문·판정 내용은 남기지 않는다 — 수치와 우리 어휘만 (logging.md · S-4)
        log.info("이슈 분석 완료 repositoryId={} {}", repositoryId, result);
        return result;
    }

    /**
     * 이슈 1건. <b>실패가 배치를 죽이지 않는다</b> (NFR-4).
     *
     * <p>한 이슈의 이상 응답으로 스캔 전체가 멈추면, 고장난 이슈 하나가 저장소 전체의
     * 파이프라인을 인질로 잡는다.
     */
    private void analyzeOne(AnalyzableIssue issue, Counter counter) {
        Optional<Long> created = writer.beginAnalysis(issue.id());
        if (created.isEmpty()) {
            // 경쟁에서 졌다 — UNIQUE(issue_id) 가 막았다. 멱등이 작동한 것이다
            counter.skipped++;
            return;
        }
        Long candidateId = created.get();

        MDC.put("candidateId", String.valueOf(candidateId));
        try {
            // ╔═══ 트랜잭션 밖 — 수십 초가 걸린다 ═══╗
            IssueAnalysis analysis = analyst.analyze(candidateId, issue);
            // ╚══════════════════════════════════════╝

            boolean reject = shouldReject(analysis);
            writer.completeAnalysis(candidateId, analysis, reject);
            if (reject) {
                counter.rejected++;
                log.info("후보 기각 candidateId={} feasible={} confidence={}",
                        candidateId, analysis.implementationFeasible(), analysis.confidence());
            } else {
                counter.analyzed++;
            }
        } catch (AnalysisRejectedException e) {
            // 스키마 검증 실패 — 파싱 실패를 성공으로 처리하지 않는다 (FR-2).
            // ⚠ AgentRun 은 SUCCEEDED 로 남는다. 호출은 성공했고 토큰도 나갔다 —
            //   비용 장부와 파이프라인 판정은 세는 것이 다르다
            log.warn("분석 응답이 스키마를 만족하지 못했다 candidateId={} — FAILED: {}",
                    candidateId, e.getMessage());
            writer.failAnalysis(candidateId);
            counter.failed++;
        } catch (LlmException e) {
            // 타임아웃·절단·5xx. 전송 계층 재시도는 이미 소진됐다 (Q-6 — 파이프라인 재시도 없음).
            // ⚠ 예외 원문을 찍지 않는다 — 요청 URL·프롬프트 조각이 실려 올 수 있다 (S-4)
            log.warn("분석 호출이 실패했다 candidateId={} reason={} — FAILED",
                    candidateId, e.reason());
            writer.failAnalysis(candidateId);
            counter.failed++;
        } finally {
            MDC.remove("candidateId");
        }
    }

    /**
     * 🔴 <b>판정은 여기서 한다.</b> 모델에게 「기각할까」를 묻지 않는다 — 물으면 임계 정책이
     * 모델 안으로 들어가 설정으로 바꿀 수 없게 된다.
     *
     * <table border="1">
     *   <caption>기각 사유 — {@code codemaps/domain.md} 전이표가 정본이다</caption>
     *   <tr><th>조건</th><th>왜</th></tr>
     *   <tr><td>{@code implementationFeasible=false}</td><td>무엇을 고쳐야 하는지 모른다</td></tr>
     *   <tr><td>{@code breakingChange=true}</td><td>호환성을 깨는 PR 은 자동화가 낼 것이 아니다.
     *       규칙 필터도 {@code FilterReason.BREAKING_CHANGE} 를 {@code REJECTED} 로 둔다 —
     *       두 단계가 <b>같은 판정</b>을 해야 한다</td></tr>
     *   <tr><td>{@code confidence < min-confidence}</td><td>확신 없는 판정 위에 30분짜리
     *       구현을 얹지 않는다</td></tr>
     * </table>
     *
     * <p>⚠️ {@code REJECTED} 는 <b>종단</b>이다. 임계를 나중에 낮춰도 이미 걸러진 후보는
     * 돌아오지 않는다 — 기본값을 느슨하게 잡은 이유다.
     */
    private boolean shouldReject(IssueAnalysis analysis) {
        return !analysis.implementationFeasible()
                || analysis.breakingChange()
                || analysis.confidence().compareTo(properties.minConfidence()) < 0;
    }

    /**
     * 🔴 대외 호출이 트랜잭션 안에 들어가는 것을 막는다.
     *
     * <p>이 클래스는 {@code @Transactional} 을 붙이지 않았지만, 호출자(#14 스케줄러·컨트롤러)가
     * 감싸면 <b>규율이 조용히 깨진다.</b> 증상이 「느리다」뿐이라 리뷰에서도 놓치기 쉽다.
     * 배치는 이슈 수십 건 × 수십 초이므로 커넥션 하나가 수십 분 잡힌다.
     */
    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "분석 배치를 트랜잭션 안에서 부를 수 없다 — LLM 호출이 커넥션을 점유한다. "
                            + "호출자의 @Transactional 을 제거한다 (architecture.md 규율)");
        }
    }

    /** 집계용 가변 카운터. 메서드 경계를 넘어 세어야 해서 record 로 두지 않았다. */
    private static final class Counter {
        private int analyzed;
        private int rejected;
        private int failed;
        private int skipped;
    }
}
