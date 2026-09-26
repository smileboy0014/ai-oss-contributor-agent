package com.ossagent.candidate.application;

import com.ossagent.candidate.adapter.out.persistence.AgentRunRepository;
import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.adapter.out.persistence.GeneratedChangeRepository;
import com.ossagent.candidate.domain.AgentRun;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.GeneratedChange;
import com.ossagent.candidate.domain.PullRequest;
import com.ossagent.support.secret.TokenRedactor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후보 조회. <b>읽기 전용</b>이다 — 상태 전이 메서드를 부르지 않는다.
 *
 * <h2>🔴 S-4 — 이 클래스가 유출을 막는 지점이다</h2>
 *
 * <p>DB 의 외부 텍스트가 HTTP 로 나가는 통로가 여기다. 두 가지로 막는다.
 *
 * <ul>
 *   <li><b>담지 않는다</b> — {@code diff}·{@code testResult}·{@code reviewResult} 본문은 뷰에 넣지
 *       않는다. 크기·해시·존재 여부만. 스크럽보다 강하다</li>
 *   <li><b>스크럽한다</b> — {@code analysis}·{@code errorMessage} 는 노출이 요구되므로
 *       {@link TokenRedactor} 를 거친다</li>
 * </ul>
 *
 * <p>🔴 <b>목록은 외부 텍스트를 하나도 싣지 않는다.</b> 유출면이 상세 1건으로 좁아지고,
 * TEXT 컬럼을 읽지 않아 성능도 같은 방향이다.
 */
@Service
public class FindCandidatesUseCase {

    /** 식별 용도다. 전체를 줄 이유가 없다. */
    private static final int HASH_PREFIX_LENGTH = 12;

    private final ContributionCandidateRepository candidates;
    private final AgentRunRepository runs;
    private final GeneratedChangeRepository changes;

    public FindCandidatesUseCase(ContributionCandidateRepository candidates,
            AgentRunRepository runs, GeneratedChangeRepository changes) {
        this.candidates = candidates;
        this.runs = runs;
        this.changes = changes;
    }

    /**
     * 목록 — 프로젝션으로 읽어 TEXT 컬럼을 건드리지 않는다.
     *
     * <p>정렬은 쿼리가 {@code ORDER BY id DESC} 로 고정한다. {@code Pageable} 의 sort 를 쓰지 않는
     * 이유는 정렬이 <b>계약</b>이기 때문이다 — 호출자가 바꾸면 페이지 경계가 흔들린다.
     */
    @Transactional(readOnly = true)
    public Page<CandidateSummaryView> findAll(CandidateQuery query, int page, int size) {
        return candidates.findSummaries(query.status(), query.difficulty(), query.minConfidence(),
                PageRequest.of(page, size));
    }

    /**
     * 상세.
     *
     * <p>🔴 스크럽을 여기서 끝낸다. 컨트롤러로 넘어간 뒤에는 트랜잭션이 닫혀 있고, 스크럽 책임이
     * adapter 로 새면 빠뜨리는 순간 토큰이 나간다.
     *
     * @throws CandidateNotFoundException 없는 id
     */
    @Transactional(readOnly = true)
    public CandidateDetailView findDetail(Long id) {
        ContributionCandidate candidate = candidates.findById(id)
                .orElseThrow(() -> new CandidateNotFoundException(id));

        List<CandidateDetailView.RunView> runViews =
                runs.findByCandidateIdOrderByStartedAtAsc(id).stream()
                        .map(FindCandidatesUseCase::toRunView)
                        .toList();

        CandidateDetailView.ChangeView latestChange =
                changes.findFirstByCandidateIdOrderByCreatedAtDesc(id)
                        .map(FindCandidatesUseCase::toChangeView)
                        .orElse(null);

        return new CandidateDetailView(
                candidate.getId(),
                candidate.getIssueId(),
                candidate.getStatus(),
                candidate.getCategory(),
                candidate.getDifficulty(),
                candidate.getEstimatedFiles(),
                candidate.getEstimatedLoc(),
                candidate.getImplementationFeasible(),
                candidate.getBreakingChange(),
                candidate.getConfidence(),
                candidate.getAttempt(),
                // 🔴 LLM 응답 — 적재 측 방어가 없다. 여기가 유일한 그물이다 (S-4)
                TokenRedactor.redact(candidate.getAnalysis()),
                candidate.getSelectedAt(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt(),
                runViews,
                latestChange,
                changes.countByCandidateId(id),
                toPrView(candidate.getPullRequest()));
    }

    private static CandidateDetailView.RunView toRunView(AgentRun run) {
        return new CandidateDetailView.RunView(
                run.getId(),
                run.getStage(),
                run.getAttempt(),
                run.getStatus(),
                run.getInputTokens(),
                run.getOutputTokens(),
                // 적재 측(AgentRun.fail)이 이미 한 번 거르지만 다시 거른다 —
                // 그 경로를 타지 않고 들어온 행(직접 SQL·마이그레이션·이전 버전)이 있을 수 있다
                TokenRedactor.redact(run.getErrorMessage()),
                run.getStartedAt(),
                run.getFinishedAt());
    }

    /** 🔴 {@code diff}·{@code testResult}·{@code reviewResult} <b>본문을 담지 않는다</b> — S-4. */
    private static CandidateDetailView.ChangeView toChangeView(GeneratedChange change) {
        String diff = change.getDiff();
        return new CandidateDetailView.ChangeView(
                change.getId(),
                change.getBranchName(),
                change.getCommitSha(),
                diff == null ? 0 : diff.length(),
                sha256Prefix(diff),
                change.getTestResult() != null,
                change.getReviewResult() != null,
                change.getCreatedAt());
    }

    /**
     * 🔴 <b>URL 도 스크럽한다.</b> 지금은 이 컬럼들을 채우는 코드가 없어 무해하지만,
     * <b>#22(Fork push)가 무엇을 넣느냐에 이 API 의 안전이 걸린다.</b>
     * push URL 에 자격증명을 박는 형태({@code https://x-access-token:TOKEN@github.com/...})는
     * 가장 흔한 구현이고, 그렇게 들어오는 순간 이 조회 API 가 토큰을 HTTP 로 내보낸다 — S-4.
     *
     * <p>{@code redact()} 는 토큰이 없으면 입력을 그대로 돌려주므로 비용이 없다.
     * <b>적재 측을 믿지 않는 것</b>은 {@code errorMessage} 에 적용한 논리와 같다.
     */
    private static CandidateDetailView.PrView toPrView(PullRequest pullRequest) {
        if (pullRequest == null) {
            return null;
        }
        return new CandidateDetailView.PrView(
                pullRequest.getId(),
                TokenRedactor.redact(pullRequest.getForkUrl()),
                pullRequest.getBranchName(),
                pullRequest.getGithubPrNumber(),
                TokenRedactor.redact(pullRequest.getPrUrl()),
                pullRequest.getStatus() == null ? null : pullRequest.getStatus().name());
    }

    /** 같은 diff 인지 식별하기 위한 것. 본문을 대신하지 않는다. */
    private static String sha256Prefix(String text) {
        if (text == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_PREFIX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JDK 구현이 제공한다. 여기 오면 플랫폼이 깨진 것이다
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
