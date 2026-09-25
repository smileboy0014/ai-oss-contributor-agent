package com.ossagent.repository.domain;

import com.ossagent.support.testing.FakeAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ContributionRuleInterpreter} 의 테스트 대역.
 *
 * <p>기본값이 {@link RuleReading#undetermined()} 인 것이 의도다 — <b>보류가 안전한 쪽</b>이라
 * 스크립트를 깜빡한 테스트가 「허용」으로 통과하지 않는다.
 *
 * <p>🔴 일시적 실패({@code LlmTransientException})도 주입할 수 있어야 한다. 그것이
 * 「기록 없이 중단」 경로를 검증하는 유일한 방법이다.
 */
@FakeAdapter
public class FakeContributionRuleInterpreter implements ContributionRuleInterpreter {

    private RuleReading next = RuleReading.undetermined();
    private RuntimeException failure;
    private final List<RepositoryDocuments> calls = new ArrayList<>();

    public FakeContributionRuleInterpreter given(RuleReading reading) {
        this.next = reading;
        this.failure = null;
        return this;
    }

    /** 일시적 실패 주입 — 호출자가 「아무것도 쓰지 않고 중단」하는지 본다. */
    public FakeContributionRuleInterpreter failWith(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    /** 몇 번 불렸나. 「못 읽으면 LLM 을 부르지 않는다」를 검증한다. */
    public List<RepositoryDocuments> calls() {
        return List.copyOf(calls);
    }

    public FakeContributionRuleInterpreter reset() {
        next = RuleReading.undetermined();
        failure = null;
        calls.clear();
        return this;
    }

    @Override
    public RuleReading interpret(RepositoryCoordinates coordinates, RepositoryDocuments documents) {
        calls.add(documents);
        if (failure != null) {
            throw failure;
        }
        return next;
    }
}
