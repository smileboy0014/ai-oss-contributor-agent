package com.ossagent.issue.domain;

import java.util.List;

/**
 * 이미 종료된 이슈를 배제한다 — #9 규칙 ①.
 *
 * <h2>🔴 알려진 공백 — 머지 후에도 발화하지 않는다</h2>
 *
 * <p>{@code Issue.state} 는 수집 시 {@code "open"} 으로 <b>고정</b>된다. 조회가
 * {@code state=open} 이라 닫힌 이슈를 애초에 읽지 않고, 한 번 저장된 이슈는 나중에
 * 닫혀도 <b>영원히 open 으로 남는다</b>(#8 이 javadoc 에 명시적으로 넘긴 공백이다).
 *
 * <p>그래서 이 규칙은 <b>구현돼 있으나 실제로는 아무것도 배제하지 않는다.</b>
 * 데이터 정확성은 #14 의 몫이고, 그때 이 규칙이 그대로 발화한다.
 *
 * <p>⚠ 규칙을 지금 넣는 이유 — 수집 쿼리를 {@code state=all} 로 넓히는 순간 필요한 것이
 * 이 판정이다. 규칙이 없으면 그 변경이 <b>닫힌 이슈를 후보로 만든다.</b>
 * 규칙만 먼저 세워 두면 #14 는 데이터만 고치면 된다.
 *
 * <p>⚠ 이 공백을 「구현했다」로 적지 않는다. 계획서·PR 본문·테스트 3곳에 남긴다.
 */
public final class ClosedIssueRule implements FilterRule {

    private static final String STATE_CLOSED = "closed";

    @Override
    public List<FilterReason> evaluate(Issue issue) {
        return isClosed(issue) ? List.of(FilterReason.CLOSED) : List.of();
    }

    private static boolean isClosed(Issue issue) {
        String state = issue.getState();
        return state != null && STATE_CLOSED.equalsIgnoreCase(state.trim());
    }
}
