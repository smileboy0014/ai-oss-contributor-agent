package com.ossagent.candidate.domain;

import java.math.BigDecimal;

/**
 * {@link IssueAnalysis} 값 픽스처 — {@code testing-philosophy.md} 픽스처 규약.
 *
 * <p>static factory 만 둔다. 상태를 갖지 않으므로 테스트 간 누수가 없다.
 *
 * <p>⚠️ <b>애그리거트를 넘어 공유하지 않는다.</b> {@code issue}·{@code repository} 테스트가
 * 이것을 쓰면 규율 ④ 가 막는 의존이 테스트를 통해 되살아난다.
 */
final class IssueAnalysisFixtures {

    private IssueAnalysisFixtures() {
    }

    /** 통과하는 평범한 판정. */
    static IssueAnalysis feasible() {
        return builder().build();
    }

    /** 구현 불가 판정 — {@code REJECTED} 로 가는 입력. */
    static IssueAnalysis infeasible() {
        return builder().implementationFeasible(false).build();
    }

    /** 신뢰도만 바꾼 판정 — 임계 경계 테스트용. */
    static IssueAnalysis withConfidence(String confidence) {
        return builder().confidence(new BigDecimal(confidence)).build();
    }

    /** 요약만 바꾼 판정 — 스크럽 테스트용. */
    static IssueAnalysis withSummary(String summary) {
        return builder().summary(summary).build();
    }

    static Builder builder() {
        return new Builder();
    }

    /**
     * 테스트 전용 빌더.
     *
     * <p>운영 코드에 {@code @Builder} 를 금지한 것(Q-7)과 충돌하지 않는다 — 그 금지는
     * <b>엔티티</b>에 불법 상태를 만드는 생성 경로가 늘어나는 것을 막는 규칙이고,
     * 이것은 {@code src/test} 의 값 타입 조립기다. {@link IssueAnalysis} 의 불변식은
     * 여기서도 그대로 검증된다 — 생성자를 통과해야 하기 때문이다.
     */
    static final class Builder {

        private String category = "enhancement";
        private IssueAnalysis.Difficulty difficulty = IssueAnalysis.Difficulty.MEDIUM;
        private boolean implementationFeasible = true;
        private int estimatedFiles = 4;
        private int estimatedLoc = 120;
        private boolean testRequired = true;
        private boolean breakingChange = false;
        private BigDecimal confidence = new BigDecimal("0.87");
        private String summary = "재현 조건과 수정 방향이 명확하다";

        Builder category(String value) {
            this.category = value;
            return this;
        }

        Builder difficulty(IssueAnalysis.Difficulty value) {
            this.difficulty = value;
            return this;
        }

        Builder implementationFeasible(boolean value) {
            this.implementationFeasible = value;
            return this;
        }

        Builder estimatedFiles(int value) {
            this.estimatedFiles = value;
            return this;
        }

        Builder estimatedLoc(int value) {
            this.estimatedLoc = value;
            return this;
        }

        Builder breakingChange(boolean value) {
            this.breakingChange = value;
            return this;
        }

        Builder confidence(BigDecimal value) {
            this.confidence = value;
            return this;
        }

        Builder summary(String value) {
            this.summary = value;
            return this;
        }

        IssueAnalysis build() {
            return new IssueAnalysis(category, difficulty, implementationFeasible, estimatedFiles,
                    estimatedLoc, testRequired, breakingChange, confidence, summary);
        }
    }
}
