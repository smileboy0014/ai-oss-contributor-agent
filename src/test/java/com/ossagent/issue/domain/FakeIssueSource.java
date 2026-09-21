package com.ossagent.issue.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@link IssueSource} 의 테스트 대역 — Q-9 가 닫힐 때까지의 자체 페이크.
 *
 * <p>페이지를 큐로 넣어 두면 호출 순서대로 돌려준다. 증분 수집(#8)의 커서 진전과
 * 멱등성(스캔 재실행이 중복 후보를 만들지 않는다)을 검증할 때 이 순서가 필요하다.
 */
public class FakeIssueSource implements IssueSource {

    private final Deque<IssuePage> pages = new ArrayDeque<>();
    private final List<IssueQuery> queries = new ArrayList<>();
    private RuntimeException failure;

    public FakeIssueSource given(IssuePage page) {
        pages.addLast(page);
        return this;
    }

    public FakeIssueSource givenIssues(IssueSnapshot... issues) {
        return given(new IssuePage(List.of(issues), null, false, false));
    }

    /** 레이트리밋·권한 오류 경로 재현용. */
    public FakeIssueSource failWith(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    /** 실제로 들어온 조회 조건. 커서가 올바로 전달되는지 확인한다. */
    public List<IssueQuery> queries() {
        return List.copyOf(queries);
    }

    @Override
    public IssuePage fetchOpenIssues(IssueQuery query) {
        if (failure != null) {
            throw failure;
        }
        queries.add(query);
        // 더 넣어 둔 페이지가 없으면 「빈 마지막 페이지」다 — 무한 루프를 만들지 않는다
        return pages.isEmpty() ? new IssuePage(List.of(), null, false, false) : pages.removeFirst();
    }
}
