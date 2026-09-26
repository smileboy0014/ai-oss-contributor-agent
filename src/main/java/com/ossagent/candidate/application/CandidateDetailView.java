package com.ossagent.candidate.application;

import com.ossagent.candidate.domain.AgentRun;
import com.ossagent.candidate.domain.CandidateStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 상세 조회의 <b>UseCase 산출물</b>. 트랜잭션 안에서 완전히 물질화되고 <b>스크럽까지 끝난</b> 상태다.
 *
 * <p>⚠️ <b>왜 web DTO 를 직접 반환하지 않나</b> — 의존은 안쪽으로 향해야 한다(규율 ①).
 * {@code application} 이 {@code adapter/in/web/dto} 를 import 하면 방향이 뒤집힌다.
 * 컨트롤러가 이 뷰를 web DTO 로 매핑한다 — {@code RegisterRepositoryUseCase} → {@code RepositoryResponse}
 * 와 같은 패턴이다.
 *
 * <p>⚠️ <b>왜 엔티티를 반환하지 않나</b> — 두 가지다.
 * ① {@code open-in-view: false} + {@code pullRequest} 가 LAZY 라 컨트롤러에서 만지면
 *    {@code LazyInitializationException} 이다.
 * ② 🔴 <b>스크럽이 트랜잭션 안에서 끝나야 한다.</b> 엔티티를 넘기면 스크럽 책임이 adapter 로 새고,
 *    빠뜨리는 순간 토큰이 HTTP 로 나간다(S-4).
 */
public record CandidateDetailView(
        Long id,
        Long issueId,
        CandidateStatus status,
        String category,
        String difficulty,
        Integer estimatedFiles,
        Integer estimatedLoc,
        Boolean implementationFeasible,
        Boolean breakingChange,
        BigDecimal confidence,
        Integer attempt,
        String analysis,
        Instant selectedAt,
        Instant createdAt,
        Instant updatedAt,
        List<RunView> runs,
        ChangeView latestChange,
        long totalChanges,
        PrView pullRequest) {

    /** 실행 이력 1건. {@code errorMessage} 는 <b>스크럽된 값</b>이다. */
    public record RunView(
            Long id,
            AgentRun.Stage stage,
            Integer attempt,
            AgentRun.RunStatus status,
            Integer inputTokens,
            Integer outputTokens,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt) {
    }

    /**
     * 생성 변경분 <b>요약</b> — 본문이 없다.
     *
     * <p>{@code diff} 는 행마다 수십 KB 이고 대상 저장소가 커밋해 둔 시크릿이 섞여 있을 수 있다.
     * 같은 diff 인지 식별하는 데 필요한 것은 크기와 해시뿐이다 — S-4.
     */
    public record ChangeView(
            Long id,
            String branchName,
            String commitSha,
            int diffSize,
            String diffSha256,
            boolean hasTestResult,
            boolean hasReviewResult,
            Instant createdAt) {
    }

    /** Draft PR 메타데이터. {@code status} 는 값이 {@code DRAFT} 하나뿐이다 — S-2. */
    public record PrView(
            Long id,
            String forkUrl,
            String branchName,
            Integer githubPrNumber,
            String prUrl,
            String status) {
    }
}
