package com.ossagent.candidate.domain;

import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.support.testing.FakeAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link DiffReviewer} 의 테스트 대역.
 *
 * <p>🔴 <b>실패 모드를 재현할 수 있다.</b> 「항상 통과만 반환하는 페이크」는 이 단계의
 * 게이트를 검증하지 못한다 — 이 제품의 품질 축은 「나쁜 결과를 걸러내는가」다.
 *
 * <p>재현 가능한 것 — 통과 · 변경 요구 · <b>판정 불가</b> · 상한 초과 · 스키마 위반 ·
 * 전송 실패({@code LlmException}). 🔴 <b>「판정 불가」와 「변경 요구」를 가를 수 있어야
 * #21 의 재시도 분기를 테스트할 수 있다.</b>
 */
@FakeAdapter
public class FakeDiffReviewer implements DiffReviewer {

    private final List<AgentRunContext> calls = new ArrayList<>();
    private DiffReview scripted = passing();
    private RuntimeException failure;

    /** 🔴 바퀴별 결과 — 고정 하나로는 #21 의 루프를 구분할 수 없다. */
    private final java.util.Deque<DiffReview> queue = new java.util.ArrayDeque<>();

    public FakeDiffReviewer given(DiffReview review) {
        this.scripted = review;
        this.failure = null;
        return this;
    }

    /** 전송 실패·상한 초과·스키마 위반을 재현한다. */
    public FakeDiffReviewer givenFailure(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    public List<AgentRunContext> calls() {
        return List.copyOf(calls);
    }

    /** 바퀴 순서대로. 다 쓰면 마지막 것이 반복된다. */
    public FakeDiffReviewer givenInOrder(DiffReview... reviews) {
        queue.clear();
        queue.addAll(java.util.List.of(reviews));
        this.failure = null;
        return this;
    }

    /** 싱글턴이라 앞 테스트의 흔적이 남는다. */
    public FakeDiffReviewer reset() {
        calls.clear();
        queue.clear();
        scripted = passing();
        failure = null;
        return this;
    }

    @Override
    public DiffReview review(AgentRunContext context, DiffReviewRequest request) {
        calls.add(context);
        if (failure != null) {
            throw failure;
        }
        if (queue.size() > 1) {
            return queue.poll();
        }
        return queue.isEmpty() ? scripted : queue.peek();
    }

    /** 통과 — 테스트가 스크립트에 쓴다. */
    public static DiffReview passingReview() {
        return passing();
    }

    /** 변경 요구 — <b>재시도가 의미 있는</b> 판정이다. */
    public static DiffReview changesRequested(String finding) {
        return new DiffReview(ReviewVerdict.CHANGES_REQUESTED, false, true, null, true,
                "고칠 것이 있다", List.of(finding));
    }

    private static DiffReview passing() {
        return new DiffReview(ReviewVerdict.PASS, true, true, null, true,
                "문제를 찾지 못했다", List.of());
    }
}
