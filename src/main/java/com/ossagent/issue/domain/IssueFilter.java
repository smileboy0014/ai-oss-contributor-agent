package com.ossagent.issue.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 규칙 필터 — LLM 이전의 1차 배제 (#9).
 *
 * <h2>🔴 모든 규칙을 평가한다</h2>
 *
 * <p>첫 배제에서 끊지 않는다. 끊으면 저장되는 사유가 <b>규칙 평가 순서의 함수</b>가 되고,
 * 「어느 규칙이 얼마나 거르는가」를 볼 수 없게 된다 — FR-2 가 사유를 남기라고 한 이유가
 * 바로 그 분포다. 규칙은 전부 순수 함수라 전량 평가 비용이 사실상 없다.
 *
 * <h2>집계</h2>
 *
 * <pre>
 * REJECTED 가 하나라도 있으면  → REJECTED
 * 아니고 UNDECIDED 가 있으면   → UNDECIDED
 * 전부 통과면                  → PASSED
 * </pre>
 *
 * <p>🔴 {@code PASSED} 가 <b>도달 가능해야 한다.</b> 입력과 무관하게 늘 {@code UNDECIDED}
 * 를 내는 규칙을 하나라도 넣으면 {@code PASSED} 는 죽은 값이 되고, 하류 #11 이
 * {@code UNDECIDED} 를 통과로 취급할 수밖에 없다 — 「판정하지 않았다」를 「통과」로
 * 뭉개는 것을 막겠다는 명분이 한 층 위에서 그대로 재현된다.
 *
 * <h2>⚠ 여기 없는 규칙 — 활성 PR 존재</h2>
 *
 * <p>확인하려면 Search API(30 req/min) 또는 이슈당 Timeline 1호출이다. 수천 건 전량에
 * 그 비용을 치르면 아끼려던 것보다 더 쓴다. <b>후보가 처음으로 극소수가 되는 지점</b>,
 * 즉 필터를 통과해 LLM 분석에 들어가기 직전(#11 입구)에서 1회 확인하고, PR 생성 직전
 * (#22)에서 1회 재확인한다.
 *
 * <p>🔴 이것은 S-2 방어의 <b>이전</b>이지 소멸이 아니다. 「활성 PR 이 이미 있는 이슈에
 * Draft PR 을 하나 더」는 S-2 가 막으려는 바로 그 행위다. 옮겨간 자리에서
 * <b>권고가 아니라 차단 게이트</b>로 구현돼야 한다.
 */
public final class IssueFilter {

    private final List<FilterRule> rules;

    public IssueFilter(List<FilterRule> rules) {
        if (rules == null || rules.isEmpty()) {
            // 규칙이 없으면 전건 PASSED 다. 「필터를 돌렸다」는 사실만 남고 아무것도 걸러지지
            // 않는 상태가 조용히 성립한다 — 설정 실수로 그렇게 되는 것을 막는다
            throw new IllegalArgumentException("규칙이 하나도 없다 — 필터가 아무것도 걸러내지 않는다");
        }
        this.rules = List.copyOf(rules);
    }

    /** 기본 구성. 규칙 순서는 판정에 영향을 주지 않는다 — 전부 평가하고 집계한다. */
    public static IssueFilter of(int minBodyLength, int maxCommentCount) {
        return new IssueFilter(List.of(
                new ClosedIssueRule(),
                new UnclearRequirementRule(minBodyLength, maxCommentCount),
                new LargeChangeRule()));
    }

    public FilterVerdict evaluate(Issue issue) {
        if (issue == null) {
            throw new IllegalArgumentException("이슈는 필수다");
        }
        List<FilterReason> reasons = new ArrayList<>();
        for (FilterRule rule : rules) {
            reasons.addAll(rule.evaluate(issue));
        }
        return FilterVerdict.of(reasons, LabelPriority.scoreOf(issue));
    }
}
