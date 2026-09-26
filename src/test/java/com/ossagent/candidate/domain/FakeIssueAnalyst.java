package com.ossagent.candidate.domain;

import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.support.testing.FakeAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@link IssueAnalyst} 대역.
 *
 * <p>🔴 <b>실패 모드를 재현할 수 있어야 한다</b> — 「항상 성공만 반환하는 페이크」는
 * {@code FAILED} 전이도 S-6 게이트도 검증하지 못한다.
 *
 * <p>🔴 <b>호출을 센다.</b> 「후보가 안 생겼다」만 단언하면 <b>분석은 다 하고 저장만 안 하는
 * 구현</b>도 통과한다 — 토큰은 그대로 태운다. S-5 게이트 테스트가 이 카운터를 본다.
 *
 * <p>⚠️ 대역은 싱글턴이고 컨텍스트는 테스트 클래스 사이에 캐시된다.
 * 호출 기록을 단언하는 테스트는 {@code @BeforeEach} 에서 {@link #reset()} 한다.
 */
@FakeAdapter
public class FakeIssueAnalyst implements IssueAnalyst {

    private Function<AnalyzableIssue, IssueAnalysis> behavior = issue -> defaultAnalysis();
    private final List<AnalyzableIssue> calls = new ArrayList<>();

    /** 모든 호출이 같은 결과를 낸다. */
    public FakeIssueAnalyst given(IssueAnalysis analysis) {
        this.behavior = issue -> analysis;
        return this;
    }

    /** 이슈마다 다른 결과 — 「한 건만 실패」 같은 시나리오용. */
    public FakeIssueAnalyst givenPerIssue(Function<AnalyzableIssue, IssueAnalysis> function) {
        this.behavior = function;
        return this;
    }

    /** 모든 호출이 실패한다 — {@code AnalysisRejectedException}·{@code LlmException} 등. */
    public FakeIssueAnalyst failWith(RuntimeException exception) {
        this.behavior = issue -> {
            throw exception;
        };
        return this;
    }

    public List<AnalyzableIssue> calls() {
        return List.copyOf(calls);
    }

    /** 🔴 S-5 게이트가 「토큰을 쓰지 않았다」를 단언하는 지점. */
    public int callCount() {
        return calls.size();
    }

    public FakeIssueAnalyst reset() {
        behavior = issue -> defaultAnalysis();
        calls.clear();
        return this;
    }

    @Override
    public IssueAnalysis analyze(Long candidateId, AnalyzableIssue issue) {
        if (candidateId == null) {
            // 실물과 같은 계약을 지킨다 — 대역이 더 관대하면 테스트가 거짓 초록이 된다
            throw new IllegalArgumentException("candidateId 는 필수다");
        }
        calls.add(issue);
        return behavior.apply(issue);
    }

    private static IssueAnalysis defaultAnalysis() {
        return new IssueAnalysis("enhancement", IssueAnalysis.Difficulty.MEDIUM, true, 4, 120,
                true, false, new java.math.BigDecimal("0.87"), "기본 판정");
    }
}
