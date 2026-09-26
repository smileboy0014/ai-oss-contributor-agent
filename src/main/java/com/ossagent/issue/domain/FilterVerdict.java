package com.ossagent.issue.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 이슈 한 건에 대한 규칙 필터의 결론 — #9.
 *
 * <h2>🔴 사유를 전부 담는다</h2>
 *
 * <p>첫 매치에서 끊지(short-circuit) 않는다. 끊으면 사유 분포가 <b>규칙 평가 순서의
 * 함수</b>가 되고, FR-2 가 노린 「분포를 보고 규칙을 고친다」가 불가능해진다.
 * 대표 사유 하나만 저장하는 것도 같은 이유로 하지 않았다 — 저장된 통계가 편향된다.
 *
 * @param outcome  집계 판정. 사유들 중 가장 강한 것이다
 * @param reasons  걸린 사유 전부. {@link FilterReason} 선언 순서로 정규화된다 —
 *                 같은 입력이 같은 문자열로 저장돼야 재현이 된다
 * @param priority 라벨 우선순위 점수 (FR-4). 배제된 이슈도 계산한다 — 규칙을 되돌렸을 때
 *                 다시 계산하지 않아도 되고, 점수 분포를 보는 데도 쓴다
 */
public record FilterVerdict(FilterOutcome outcome, List<FilterReason> reasons, int priority) {

    /** 저장 문자열의 구분자. {@link FilterReason} 이름에 나타나지 않는 문자여야 한다. */
    public static final String REASON_DELIMITER = ",";

    public FilterVerdict {
        if (outcome == null) {
            throw new IllegalArgumentException("판정은 필수다");
        }
        reasons = normalize(reasons);
        if (priority < 0) {
            throw new IllegalArgumentException("우선순위 점수는 음수일 수 없다: " + priority);
        }
        if (outcome == FilterOutcome.PASSED && !reasons.isEmpty()) {
            throw new IllegalArgumentException("통과인데 사유가 있다: " + reasons);
        }
        if (outcome != FilterOutcome.PASSED && reasons.isEmpty()) {
            // 사유 없는 배제는 FR-2 위반이다 — 왜 떨어졌는지 영영 알 수 없다
            throw new IllegalArgumentException("배제·보류에는 사유가 반드시 있어야 한다");
        }
    }

    /**
     * 걸린 사유들로부터 판정을 <b>집계해</b> 만든다.
     *
     * <p>판정을 호출자가 직접 정하는 생성 경로를 두지 않는다 — 「배제인데 사유가
     * {@code SHORT_BODY} 뿐」 같은 모순이 만들어진다.
     */
    public static FilterVerdict of(Collection<FilterReason> reasons, int priority) {
        List<FilterReason> normalized = normalize(reasons);
        FilterOutcome outcome = normalized.stream()
                .map(FilterReason::outcome)
                .reduce(FilterOutcome.PASSED, FilterOutcome::or);
        return new FilterVerdict(outcome, normalized, priority);
    }

    /** 저장된 문자열을 되읽는다. 모르는 코드는 조용히 버린다 — 이름이 바뀐 옛 행일 수 있다. */
    public static List<FilterReason> parseReasons(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return normalize(Arrays.stream(stored.split(REASON_DELIMITER))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .map(FilterVerdict::toReason)
                .filter(Objects::nonNull)
                .toList());
    }

    /** {@code issue.filter_reason} 에 들어가는 값. 통과면 {@code null} 이다. */
    public String reasonCodes() {
        return reasons.isEmpty() ? null
                : reasons.stream().map(Enum::name).collect(Collectors.joining(REASON_DELIMITER));
    }

    public boolean excluded() {
        return outcome.excluded();
    }

    private static List<FilterReason> normalize(Collection<FilterReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return List.of();
        }
        Set<FilterReason> unique = EnumSet.copyOf(reasons);
        return List.copyOf(unique);   // EnumSet 순회는 선언 순서다
    }

    private static FilterReason toReason(String name) {
        try {
            return FilterReason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
