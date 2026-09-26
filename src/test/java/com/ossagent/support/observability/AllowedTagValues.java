package com.ossagent.support.observability;

import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 태그 <b>값</b>으로 허용되는 어휘 — 전부 우리가 선언한 enum 과 고정 문자열이다.
 *
 * <p>🔴 두 가드가 공유한다 — {@code MetricTagRuleTest}(테스트 레지스트리)와
 * {@code ActuatorMetricsExposureTest}(실제 컨텍스트 레지스트리). 목록이 갈리면
 * <b>한쪽만 통과하는 값</b>이 생기고, 그때 「가드가 있다」는 말이 반만 참이 된다.
 *
 * <p>새 어휘가 늘면 여기 등록해야 한다. <b>그 마찰이 의도다</b> — 태그 어휘가 조용히
 * 늘어나는 것을 막는 것이 이 가드의 목적이다.
 */
final class AllowedTagValues {

    private static final Set<String> VALUES = build();

    private AllowedTagValues() {
    }

    static boolean contains(String value) {
        return VALUES.contains(value);
    }

    private static Set<String> build() {
        Set<String> values = new HashSet<>();
        Stream.of(LlmCallSite.values()).map(Enum::name).forEach(values::add);
        Stream.of(LlmOutcome.values()).map(Enum::name).forEach(values::add);
        Stream.of(PipelineStage.values()).map(Enum::name).forEach(values::add);
        Stream.of(StageOutcome.values()).map(Enum::name).forEach(values::add);
        Stream.of(SafetyClause.values()).map(Enum::name).forEach(values::add);
        Stream.of(GateOutcome.values()).map(Enum::name).forEach(values::add);
        Stream.of(AnalysisOutcome.values()).map(Enum::name).forEach(values::add);
        Stream.of(ContributionNotAllowedException.Reason.values()).map(Enum::name)
                .forEach(values::add);
        // 후보 상태 게이지 — 11개 상태가 태그 값이 된다
        Stream.of(CandidateStatus.values()).map(Enum::name).forEach(values::add);
        // 고정 문자열 — 「사유 없음」과 토큰 방향
        values.add("NONE");
        values.add("input");
        values.add("output");
        return Set.copyOf(values);
    }
}
