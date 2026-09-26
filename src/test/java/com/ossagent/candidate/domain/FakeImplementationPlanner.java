package com.ossagent.candidate.domain;

import com.ossagent.support.testing.FakeAdapter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@link ImplementationPlanner} 의 테스트 대역 — 이슈 #16.
 *
 * <p>🔴 <b>실패 모드를 재현할 수 있어야 한다</b>({@code testing-philosophy.md}).
 * 「항상 성공만 반환하는 페이크」는 이 이슈의 게이트를 하나도 검증하지 못한다 —
 * 검증 거부 → 재생성 → 상한 소진이 전부 실패 경로이기 때문이다.
 *
 * <p>그래서 응답을 <b>큐</b>로 준다. 「1차는 나쁜 계획, 2차는 좋은 계획」을 세울 수 있어야
 * 재생성이 실제로 도는지 확인할 수 있다.
 */
@FakeAdapter
public class FakeImplementationPlanner implements ImplementationPlanner {

    private final Deque<Object> responses = new ArrayDeque<>();
    private final List<PlanningInput> received = new ArrayList<>();

    /** 다음 호출이 이 계획을 돌려준다. 여러 번 부르면 순서대로 나온다 */
    public FakeImplementationPlanner willReturn(ImplementationPlan plan) {
        responses.add(plan);
        return this;
    }

    /** 다음 호출이 이 예외를 던진다 — 스키마 위반·LLM 실패 경로를 재현한다 */
    public FakeImplementationPlanner willThrow(RuntimeException exception) {
        responses.add(exception);
        return this;
    }

    /**
     * 실제로 받은 입력들. <b>재생성 프롬프트에 거부 사유가 실렸는가</b>를 여기서 단언한다 —
     * 싣지 않으면 재생성이 같은 실수를 반복하고 예산만 태운다.
     */
    public List<PlanningInput> received() {
        return List.copyOf(received);
    }

    public int callCount() {
        return received.size();
    }

    @Override
    public ImplementationPlan plan(Long candidateId, PlanningInput input) {
        received.add(input);
        Object next = responses.poll();
        if (next == null) {
            // 🔴 조용히 성공을 만들어 내지 않는다. 큐가 빈 것은 「상한을 넘겨 불렸다」는
            //    뜻이고, 그것이 바로 이 이슈가 막아야 할 일이다 — 테스트가 봐야 한다
            throw new IllegalStateException(
                    "페이크에 준비된 응답보다 많이 불렸다(테스트 셋업 오류 또는 상한 초과): "
                            + "callCount=" + received.size());
        }
        if (next instanceof RuntimeException exception) {
            throw exception;
        }
        return (ImplementationPlan) next;
    }
}
