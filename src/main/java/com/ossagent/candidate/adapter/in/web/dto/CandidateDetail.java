package com.ossagent.candidate.adapter.in.web.dto;

import com.ossagent.candidate.application.CandidateDetailView;
import com.ossagent.candidate.domain.AgentRun;
import com.ossagent.candidate.domain.CandidateStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 후보 상세.
 *
 * <h2>🔴 S-4 — 이 타입이 유출 경로다</h2>
 *
 * <p>DB 에 담긴 외부 텍스트를 그대로 내보내면 토큰이 HTTP 로 나간다. 두 가지로 막는다.
 *
 * <ul>
 *   <li><b>담지 않는다</b> — {@code diff}·{@code testResult}·{@code reviewResult} 는 본문이 없다.
 *       크기·해시·존재 여부만 준다. 스크럽보다 강한 방어다</li>
 *   <li><b>스크럽한다</b> — {@code analysis}·{@code errorMessage} 는 노출이 요구되므로
 *       {@code TokenRedactor} 를 거친다. 통과 지점은 UseCase 다</li>
 * </ul>
 *
 * <p>🟡 <b>스크럽이 {@code analysis} 를 완전히 닫지는 못한다.</b> {@code TokenRedactor} 는 토큰 패턴
 * 5종 + {@code Authorization} 헤더만 잡는다고 스스로 밝힌다. {@code analysis} 는 LLM 응답이고
 * 모델은 대상 저장소 컨텍스트를 받으므로, <b>모델이 대상 저장소의 DB 비밀번호나 비표준 사내
 * 토큰을 인용하면 패턴에 걸리지 않는다.</b> 1차 방어는 <b>적재 측</b>(#11)과 송신 전 프롬프트
 * 스크럽(#28)이고, 여기 redact 는 마지막 그물이다.
 *
 * @param analysis LLM 분석 결과. <b>스크럽된 불투명 문자열</b>이다.
 *                 ⚠️ #11 이 구조화 JSON 을 넣기로 하면 이 계약이 「JSON 이 박힌 문자열」이 된다 —
 *                 구조화는 그때 별도 결정이고, 지금 추측으로 굳히지 않는다
 */
public record CandidateDetail(
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
        List<AgentRunView> runs,
        ChangeSummary latestChange,
        long totalChanges,
        PullRequestView pullRequest) {

    /**
     * {@code application} 뷰 → web 응답.
     *
     * <p>스크럽은 <b>이미 끝나 있다</b> — UseCase 가 트랜잭션 안에서 한다. 여기서 다시 하지 않는 이유는
     * 「두 곳에서 하면 한 곳을 고칠 때 다른 곳을 잊는다」이고, 놓치면 안 되는 쪽이 UseCase 다(S-4).
     */
    public static CandidateDetail from(CandidateDetailView v) {
        return new CandidateDetail(v.id(), v.issueId(), v.status(), v.category(), v.difficulty(),
                v.estimatedFiles(), v.estimatedLoc(), v.implementationFeasible(), v.breakingChange(),
                v.confidence(), v.attempt(), v.analysis(), v.selectedAt(), v.createdAt(), v.updatedAt(),
                v.runs().stream().map(CandidateDetail::toRun).toList(),
                toChange(v.latestChange()), v.totalChanges(), toPr(v.pullRequest()));
    }

    private static AgentRunView toRun(CandidateDetailView.RunView r) {
        return new AgentRunView(r.id(), r.stage(), r.attempt(), r.status(), r.inputTokens(),
                r.outputTokens(), r.errorMessage(), r.startedAt(), r.finishedAt());
    }

    private static ChangeSummary toChange(CandidateDetailView.ChangeView c) {
        return c == null ? null
                : new ChangeSummary(c.id(), c.branchName(), c.commitSha(), c.diffSize(),
                        c.diffSha256(), c.hasTestResult(), c.hasReviewResult(), c.createdAt());
    }

    private static PullRequestView toPr(CandidateDetailView.PrView p) {
        return p == null ? null
                : new PullRequestView(p.id(), p.forkUrl(), p.branchName(), p.githubPrNumber(),
                        p.prUrl(), p.status());
    }

    /**
     * 실행 이력 1건.
     *
     * @param errorMessage <b>스크럽된</b> 실패 사유. 적재 측({@code AgentRun.fail})이 이미 한 번
     *                     거르지만 읽기 측에서 다시 거른다 — 그 경로를 타지 않고 들어온 행
     *                     (직접 SQL·마이그레이션·이전 버전)이 있을 수 있다
     */
    public record AgentRunView(
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
     * 생성 변경분 <b>요약</b> — 본문은 없다.
     *
     * <p>{@code diff} 는 행마다 수십 KB 다. 같은 diff 인지 식별하는 데 필요한 것은 크기와 해시이고,
     * 전문을 응답에 실을 이유가 없다 — 실으면 대상 저장소가 커밋해 둔 시크릿까지 함께 나간다(S-4).
     *
     * @param diffSha256 SHA-256 <b>앞 12자</b>. 식별 용도이므로 전체를 줄 이유가 없다
     */
    public record ChangeSummary(
            Long id,
            String branchName,
            String commitSha,
            int diffSize,
            String diffSha256,
            boolean hasTestResult,
            boolean hasReviewResult,
            Instant createdAt) {
    }

    /**
     * Draft PR 메타데이터.
     *
     * <p>{@code status} 를 굳이 싣는다 — 값이 {@code DRAFT} 하나뿐이라는 사실이 응답에서도 보이는 편이
     * 낫다(S-2). 노출해도 우회 경로가 되지 않는다.
     */
    public record PullRequestView(
            Long id,
            String forkUrl,
            String branchName,
            Integer githubPrNumber,
            String prUrl,
            String status) {
    }
}
