package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;
import java.util.List;

/**
 * AI diff 리뷰의 결과 — 이슈 #20. <b>값이고 엔티티가 아니다.</b>
 *
 * <h2>🔴 이 타입이 S-4 의 수신 쪽 방어다</h2>
 *
 * <p>송신(프롬프트)은 {@code PromptScrubber} 가 이미 막는다 — 어댑터 생성자가
 * {@code scrubber == null} 을 거부한다(#10). 그런데 <b>응답 쪽은 새로 생기는 유출구</b>다:
 * 리뷰가 diff 를 인용하면 대상 저장소의 시크릿이 <b>우리 DB 로 복제</b>되고,
 * 거기서 #13 조회 API 와 PR 본문(#23)까지 갈 수 있다.
 *
 * <p>compact 생성자가 {@link TokenRedactor} 를 강제한다. {@code String} 을 그대로 받는
 * 생성 경로가 없으므로 <b>스크럽을 건너뛸 방법이 없다</b> — {@code IssueAnalysis}(#11) ·
 * {@code ScrubbedRules}(#7) 와 같은 수법이다.
 *
 * <p>⚠️ 「대입 지점을 하나로 모은다」(강제 지점)가 아니라 <b>값 타입</b>인 이유 —
 * 대입 지점을 세는 방식은 <b>새 대입 지점이 생기면 조용히 뚫린다.</b>
 *
 * <h2>🔴 관찰값만 담는다 — 임계는 여기 없다</h2>
 *
 * <p>「몇 점이면 실패인가」를 이 타입이 정하지 않는다. {@code IssueAnalyst} 가
 * 「{@code REJECTED} 여부는 호출자가 임계로 정한다」로 못 박아 둔 것과 같다 —
 * <b>임계가 값 안에 들어가면 그것을 바꾸려고 이 타입을 고치게 되고, 리뷰에 안 보인다.</b>
 *
 * @param verdict            🔴 {@code null} 금지. 「모르는 값을 DB 에 넣지 않는다」
 * @param satisfiesIssue     이슈 요구를 충족하는가. {@code null} = 판정 불가
 * @param withinScope        계획 범위를 넘지 않았는가. {@code null} = 판정 불가
 * @param followsConventions 저장소 관습을 지켰는가. 🔴 <b>규약을 모르면 {@code null}</b> — 아래
 * @param testsAdequate      테스트가 적절한가. {@code null} = 판정 불가
 * @param summary            사람이 읽을 요약. <b>여기서 스크럽된다</b>
 * @param findings           고쳐야 할 것들. <b>항목마다 스크럽된다</b>
 */
public record DiffReview(
        ReviewVerdict verdict,
        Boolean satisfiesIssue,
        Boolean withinScope,
        Boolean followsConventions,
        Boolean testsAdequate,
        @ExternalText(ExternalText.Source.LLM_RESPONSE) String summary,
        @ExternalText(ExternalText.Source.LLM_RESPONSE) List<String> findings) {

    /** 요약 상한. 넘으면 자르지 않고 거부한다 — 잘린 요약은 사람을 오도한다. */
    private static final int MAX_SUMMARY_LENGTH = 4_000;

    /** 지적 개수 상한. 모델이 수백 건을 쏟아내면 사람이 읽지 못한다. */
    private static final int MAX_FINDINGS = 50;

    private static final int MAX_FINDING_LENGTH = 2_000;

    public DiffReview {
        if (verdict == null) {
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "verdict 가 없습니다 — 모르는 값을 DB 에 넣지 않습니다");
        }

        // 🔴 유일한 생성 경로가 스크럽을 탄다 — S-4.
        //    모델이 diff 에 있던 토큰을 요약에 되뱉을 수 있다
        summary = TokenRedactor.redact(requireText(summary));
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "summary 가 너무 깁니다 length=" + summary.length());
        }

        findings = scrubAll(findings);

        // 🔴 판정과 축이 모순이면 응답을 믿을 수 없다. PASS 인데 어떤 축이 명시적으로
        //    false 라는 것은 모델이 자기 답을 뒤집은 것이다.
        //    ⚠️ null(판정 불가)은 모순이 아니다 — 규약을 모르면 관습 축을 세울 수 없다
        if (verdict == ReviewVerdict.PASS && anyExplicitlyFalse(
                satisfiesIssue, withinScope, followsConventions, testsAdequate)) {
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "PASS 인데 위반한 축이 있습니다 — 응답이 자기모순입니다");
        }

        // 🔴 「고쳐라」라고만 하고 무엇을 고칠지 없으면 코딩 단계가 할 수 있는 것이 없다.
        //    그것은 재시도 예산만 태우는 판정이다 (Q-6)
        if (verdict == ReviewVerdict.CHANGES_REQUESTED && findings.isEmpty()) {
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "CHANGES_REQUESTED 인데 지적이 없습니다 — 고칠 대상을 알 수 없습니다");
        }
    }

    /**
     * 통과했는가. 🔴 <b>{@code verdict != CHANGES_REQUESTED} 로 쓰지 않는다</b> —
     * 그 한 줄에서 {@link ReviewVerdict#UNDETERMINED} 가 조용히 통과로 접힌다.
     */
    public boolean passed() {
        return verdict.passed();
    }

    /** 판정이 서지 않았는가. 🔴 <b>재시도 대상이 아니다</b> — 같은 입력에 같은 결과다. */
    public boolean isUndetermined() {
        return verdict == ReviewVerdict.UNDETERMINED;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            // 판정이 무엇이든 근거는 있어야 한다. UNDETERMINED 도 「왜 판정 못 했는가」가 필요하다
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "summary 가 비어 있습니다 — 근거 없는 판정은 재검토도 못 합니다");
        }
        return value;
    }

    private static List<String> scrubAll(List<String> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        if (findings.size() > MAX_FINDINGS) {
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "지적이 너무 많습니다 count=" + findings.size());
        }
        return findings.stream()
                .map(finding -> {
                    String scrubbed = TokenRedactor.redact(requireFinding(finding));
                    if (scrubbed.length() > MAX_FINDING_LENGTH) {
                        throw new DiffReviewRejectedException(
                                DiffReviewRejectedException.Reason.SCHEMA,
                                "지적이 너무 깁니다 length=" + scrubbed.length());
                    }
                    return scrubbed;
                })
                .toList();
    }

    private static String requireFinding(String finding) {
        if (finding == null || finding.isBlank()) {
            throw new DiffReviewRejectedException(DiffReviewRejectedException.Reason.SCHEMA,
                    "빈 지적이 섞여 있습니다");
        }
        return finding;
    }

    private static boolean anyExplicitlyFalse(Boolean... axes) {
        for (Boolean axis : axes) {
            if (Boolean.FALSE.equals(axis)) {
                return true;
            }
        }
        return false;
    }

    /** 🔴 내용을 포함하지 않는다. 실수로 로그에 실려도 리뷰 원문이 나가지 않게 한다. */
    @Override
    public String toString() {
        return "DiffReview[verdict=%s, findings=%d]".formatted(verdict, findings.size());
    }
}
