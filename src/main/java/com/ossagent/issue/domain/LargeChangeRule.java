package com.ossagent.issue.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 대규모 아키텍처 변경을 배제한다 — #9 규칙 ④.
 *
 * <h2>🔴 라벨만 본다. 본문 키워드를 쓰지 않는다</h2>
 *
 * <p>「refactor」·「redesign」이 본문에 있다고 대규모 변경이 아니다. 「이건 리팩토링이
 * 아니라 버그입니다」 같은 문장에도 걸린다. 오탐 비용이 배제라 되돌릴 수 없다.
 *
 * <p>라벨은 <b>메인테이너가 붙인 명시적 신호</b>라 신뢰도가 다르다. 본문은 누구나 쓰지만
 * 라벨은 권한이 있는 사람이 단다.
 *
 * <p>⚠ 라벨이 <b>없다고</b> 배제하지 않는다. 대부분의 이슈에 라벨이 없다.
 */
public final class LargeChangeRule implements FilterRule {

    /** 호환성을 깨는 변경. AI 기여의 첫 PR 로 적절하지 않다. */
    private static final List<String> BREAKING_LABELS = List.of(
            "breaking-change", "breaking");

    /** 설계 단위 작업. 이슈 하나가 여러 PR 로 쪼개진다. */
    private static final List<String> LARGE_SCOPE_LABELS = List.of(
            "epic", "rfc", "design", "architecture");

    @Override
    public List<FilterReason> evaluate(Issue issue) {
        List<FilterReason> reasons = new ArrayList<>(2);
        if (hasAny(issue, BREAKING_LABELS)) {
            reasons.add(FilterReason.BREAKING_CHANGE);
        }
        if (hasAny(issue, LARGE_SCOPE_LABELS)) {
            reasons.add(FilterReason.LARGE_SCOPE);
        }
        return List.copyOf(reasons);
    }

    private static boolean hasAny(Issue issue, List<String> labels) {
        return labels.stream().anyMatch(issue::hasLabel);
    }
}
