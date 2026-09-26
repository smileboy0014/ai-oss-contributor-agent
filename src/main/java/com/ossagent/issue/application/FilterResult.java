package com.ossagent.issue.application;

import com.ossagent.issue.domain.FilterOutcome;
import java.util.EnumMap;
import java.util.Map;

/**
 * 규칙 필터 1회 실행의 결과 — #9.
 *
 * @param judged  판정한 이슈 수
 * @param counts  판정별 건수. <b>{@code PASSED} 가 0 으로만 나온다면 규칙이 잘못됐다는
 *                신호다</b> — 도달 불가능한 판정이 있으면 하류가 보류를 통과로 뭉개게 된다
 * @param hasMore 배치 상한에 걸려 남은 것이 있다. 다음 실행이 이어받는다
 */
public record FilterResult(int judged, Map<FilterOutcome, Integer> counts, boolean hasMore) {

    public FilterResult {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }

    public static FilterResult of(Map<FilterOutcome, Integer> counts, boolean hasMore) {
        int judged = counts.values().stream().mapToInt(Integer::intValue).sum();
        return new FilterResult(judged, counts, hasMore);
    }

    public int countOf(FilterOutcome outcome) {
        return counts.getOrDefault(outcome, 0);
    }

    /** 로그 한 줄용. 판정 어휘만 담는다 — 이슈 본문·라벨은 들어가지 않는다. */
    @Override
    public String toString() {
        Map<FilterOutcome, Integer> ordered = new EnumMap<>(FilterOutcome.class);
        for (FilterOutcome outcome : FilterOutcome.values()) {
            ordered.put(outcome, countOf(outcome));
        }
        return "FilterResult[judged=%d, %s, hasMore=%s]".formatted(judged, ordered, hasMore);
    }
}
