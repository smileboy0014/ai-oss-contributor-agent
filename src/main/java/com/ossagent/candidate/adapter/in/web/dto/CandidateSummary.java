package com.ossagent.candidate.adapter.in.web.dto;

import com.ossagent.candidate.application.CandidateSummaryView;
import com.ossagent.candidate.domain.CandidateStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 목록 항목.
 *
 * <p>🔴 <b>외부 텍스트를 하나도 담지 않는다</b> — {@code analysis}·{@code diff}·{@code testResult}·
 * {@code reviewResult} 가 여기 들어오면 S-4 유출면이 목록 N건으로 넓어진다. 상세에서만 다룬다.
 * {@code CandidateDtoRuleTest} 가 규칙으로 막는다.
 *
 * <p>🔴 {@code confidence} 가 {@link BigDecimal} 인 것은 <b>컬럼이 nullable 이기 때문</b>이다.
 * {@code discover()} 가 채우지 않으므로 {@code DISCOVERED}·{@code ANALYZING} 후보는 전부 NULL 이고,
 * primitive 로 받으면 언박싱 NPE 로 <b>정상 데이터에서 목록이 500</b> 이 된다.
 *
 * @param selectedAt 사람이 고른 시각. {@code null} 이면 아직 승인 전이다 — S-6
 */
public record CandidateSummary(
        Long id,
        Long issueId,
        CandidateStatus status,
        String difficulty,
        BigDecimal confidence,
        Integer attempt,
        Instant selectedAt) {

    /** {@code application} 뷰 → web 응답. 방향은 adapter → application 이라 규율 ①을 따른다. */
    public static CandidateSummary from(CandidateSummaryView view) {
        return new CandidateSummary(view.id(), view.issueId(), view.status(), view.difficulty(),
                view.confidence(), view.attempt(), view.selectedAt());
    }
}
