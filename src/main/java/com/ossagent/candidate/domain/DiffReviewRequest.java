package com.ossagent.candidate.domain;

import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.repository.domain.ContributionConstraints;

/**
 * 리뷰에 넣을 입력 — 이슈 #20 FR-1. 「diff + 이슈 원문 + 저장소 규약」 셋이다.
 *
 * <p>⚠️ <b>두 도메인의 값 타입을 import 한다.</b> 규율 ④ 가 막는 것은 남의 <b>애그리거트
 * 엔티티·Spring Data 인터페이스</b>이고 값 타입은 허용된다 — {@code AnalyzableIssue}(#11) ·
 * {@code RepositoryCoordinates} 가 이미 같은 방식으로 도메인을 건넌다.
 *
 * <h2>🔴 {@code constraints} 는 게이트가 아니다</h2>
 *
 * <p>{@link ContributionConstraints} javadoc 이 못 박아 둔 그대로 <b>「이렇게 해라」(데이터)</b>이지
 * <b>「해도 된다」(권한)</b>가 아니다. 통행증({@code PolicyClearance})은 이 단계에 오지 않는다 —
 * <b>이미 {@code startImplementing} 에서 확인됐다.</b>
 *
 * <p>⚠️ {@link ContributionConstraints#unknown()} 이 올 수 있다(정책 행이 없을 때).
 * 그때는 「저장소 관습」 축을 <b>판정하지 못한다</b> — {@code DiffReview.followsConventions}
 * 가 {@code null} 이 된다. 🔴 <b>모르는 것을 「위반 없음」으로 적지 않는다.</b>
 *
 * @param diff        검증을 통과한 변경분. 🔴 <b>상한 초과는 어댑터가 거부한다</b> — 자르지 않는다
 * @param issue       이슈 원문. 「요구를 충족하는가」의 기준이다
 * @param constraints 저장소 규약. 「관습을 지켰는가」의 기준이다
 */
public record DiffReviewRequest(
        String diff,
        AnalyzableIssue issue,
        ContributionConstraints constraints) {

    public DiffReviewRequest {
        if (diff == null || diff.isBlank()) {
            // 변경이 없는데 리뷰를 부르는 것은 호출자의 버그다. 「통과」로 답하면 그 버그가 숨는다
            throw new IllegalArgumentException("diff 가 비어 있습니다 — 리뷰할 대상이 없습니다");
        }
        if (issue == null) {
            throw new IllegalArgumentException("이슈 원문은 필수입니다 — 요구 충족을 판정할 기준이 없습니다");
        }
        if (constraints == null) {
            // null 과 unknown() 은 다르다. 전자는 호출자의 실수고 후자는 「우리가 아는 제약이 없다」다
            throw new IllegalArgumentException(
                    "제약은 필수입니다 — 모르면 ContributionConstraints.unknown() 을 넘깁니다");
        }
    }

    /**
     * 규약을 아는가. 🔴 모르면 「관습 위반」 축을 <b>판정하지 않는다</b>
     * ({@code DiffReview.followsConventions == null}).
     *
     * <p>{@link ContributionConstraints#unknown()} 과 값으로 비교한다 — 필드를 하나씩 세면
     * 필드가 늘어날 때 이 판정이 조용히 낡는다.
     */
    public boolean hasKnownConventions() {
        return !ContributionConstraints.unknown().equals(constraints);
    }
}
