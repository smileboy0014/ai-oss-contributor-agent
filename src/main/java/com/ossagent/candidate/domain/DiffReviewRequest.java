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
 * <h2>⚠️ diff 의 시크릿은 <b>패턴 스크럽만</b> 거친다 — 경로 배제가 없다</h2>
 *
 * <p>S-4 의 방어는 둘이고 <b>서로를 대신하지 않는다</b> — {@code SecretFilePolicy}(경로를
 * 애초에 열지 않는다)와 {@code TokenRedactor}(연 파일의 알려진 패턴을 가린다).
 * <b>diff 에는 전자가 적용되지 않는다.</b> 변경분에 {@code .env}·{@code *.pem} 이 섞여 있으면
 * 그 내용이 통째로 프롬프트에 실리고, 우리가 모르는 형식의 자격증명은 패턴에 걸리지 않는다.
 *
 * <p>🔴 <b>diff 를 만드는 쪽(#18)이 그 경로를 배제해야 한다.</b> 여기서는 막을 수 없다 —
 * 받은 시점에 이미 문자열이고 어느 파일에서 왔는지 모른다.
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
     * <h2>🔴 {@code javaVersion} 을 세지 않는다</h2>
     *
     * <p>초안은 {@link ContributionConstraints#unknown()} 과 값으로 비교했다.
     * <b>그러면 {@code javaVersion} 하나만 채워진 정책도 「규약을 안다」가 된다.</b>
     * 자바 버전은 <b>빌드 대상</b>이지 기여 관습이 아니다 — sign-off·이슈 참조·테스트 동반에
     * 대해 아는 것이 하나도 없는데 모델의 {@code followsConventions = true} 를 그대로 믿게 된다.
     *
     * <p>그래서 <b>관습 축에 실제로 쓰이는 것</b>만 센다.
     *
     * <p>⚠️ 셋이 전부 {@code false} 이고 명령이 없으면 「모른다」로 본다. 「전부 필요 없다」와
     * 구분되지 않지만, <b>틀리는 방향이 안전하다</b> — 판정 불가로 두면 「모른다」가
     * 「문제 없음」이 되지 않는다(S-5).
     *
     * <p>⚠️ 필드가 늘어나면 이 목록도 봐야 한다. 값 비교로 두면 그 검토가 생략되는 대신
     * <b>무관한 필드까지 신호로 세는</b> 위 문제가 생긴다 — 목록 쪽을 택했다.
     */
    public boolean hasKnownConventions() {
        return constraints.hasBuildCommand()
                || constraints.hasTestCommand()
                || constraints.testsRequired()
                || constraints.issueReferenceRequired()
                || constraints.signoffRequired();
    }
}
