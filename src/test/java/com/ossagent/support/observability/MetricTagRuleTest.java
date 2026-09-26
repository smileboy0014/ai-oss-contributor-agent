package com.ossagent.support.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmUsage;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>메트릭 태그가 S-4 유출면이 되지 않게 한다.</b>
 *
 * <p>위험이 둘인데 <b>막는 방법은 하나</b>다.
 *
 * <ul>
 *   <li><b>시크릿</b> — 이슈 본문·LLM 응답·예외 메시지를 태그에 넣으면 토큰이 모니터링
 *       시스템으로 나간다</li>
 *   <li><b>카디널리티</b> — {@code candidateId} 같은 무한 태그는 저장소를 터뜨리고,
 *       <b>식별자를 외부로 계속 밀어낸다</b></li>
 * </ul>
 *
 * <p>둘 다 「우리가 통제하는 유한한 어휘만 태그로 쓴다」로 막힌다.
 *
 * <p>⚠️ <b>검사 범위를 우리 미터로 한정한다.</b> 「등록된 모든 미터」를 훑으면 Boot 기본
 * 미터({@code http.server.requests} 의 {@code uri} 태그 등)까지 걸려 <b>첫 실행에서
 * 무너지거나, 예외 목록을 두느라 가드가 헐거워진다.</b> 기본 미터는 우리가 태그를
 * 정하지 않으므로 별도 규칙(식별자 태그 키가 없다)으로만 본다.
 */
class MetricTagRuleTest {

    /** 소스에 토큰 패턴 리터럴을 두지 않는다 — {@code secret-scan.sh} 가 커밋을 막는다. */
    private static final String FAKE_TOKEN = "ghp_" + "a".repeat(36);

    private SimpleMeterRegistry registry;
    private PipelineMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PipelineMetrics(registry);
    }

    /** 이 PR 이 만드는 모든 계측을 한 번씩 태운다 — 가드가 0건을 검사하고 초록이 되지 않게. */
    private void exerciseAll() {
        metrics.llmCall(LlmCallSite.ANALYZE, LlmOutcome.SUCCEEDED, new LlmUsage(10, 20), 1);
        metrics.llmCall(LlmCallSite.POLICY, LlmOutcome.FAILED, null, 2);
        for (PipelineStage stage : PipelineStage.values()) {
            for (StageOutcome outcome : StageOutcome.values()) {
                metrics.pipelineStage(stage, outcome, Duration.ofMillis(5));
            }
        }
        metrics.safetyGate(SafetyClause.S5, GateOutcome.PASSED, null);
        for (ContributionNotAllowedException.Reason reason
                : ContributionNotAllowedException.Reason.values()) {
            metrics.safetyGate(SafetyClause.S5, GateOutcome.BLOCKED, reason);
        }
        for (AnalysisOutcome outcome : AnalysisOutcome.values()) {
            metrics.analysisOutcome(outcome, 1);
        }
    }

    @Test
    @DisplayName("🔴 태그 값이 전부 우리가 통제하는 어휘다 — 외부 텍스트 없음 S4")
    void 메트릭_태그에_외부_텍스트가_없다_S4() {
        exerciseAll();

        Set<String> allowed = allowedTagValues();
        List<String> unexpected = ourMeters()
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> !allowed.contains(tag.getValue()))
                .map(tag -> tag.getKey() + "=" + tag.getValue())
                .distinct()
                .toList();

        assertThat(unexpected)
                .as("""
                        메트릭 태그에 우리 어휘가 아닌 값이 있다 — S-4.
                        태그는 enum 상수와 고정 문자열에서만 와야 한다. 이슈 본문·LLM 응답·
                        예외 메시지가 들어가면 토큰이 모니터링 시스템으로 나가고,
                        식별자가 들어가면 카디널리티가 무한이 된다.

                        새 어휘를 추가했다면 이 테스트의 allowedTagValues() 에 등록한다.""")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 태그 키에 식별자가 없다 — 카디널리티 무한 S4")
    void 메트릭_태그가_식별자를_담지_않는다_S4() {
        exerciseAll();

        List<String> forbidden = ourMeters()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .filter(MetricNames.FORBIDDEN_TAG_KEYS::contains)
                .distinct()
                .toList();

        assertThat(forbidden)
                .as("candidateId·repositoryId 같은 태그는 값의 수에 상한이 없다 — "
                        + "메트릭 저장소를 터뜨리고 식별자를 외부로 계속 밀어낸다")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠ 양성 대조 — 가드가 0건을 검사하고 초록이 되지 않는다")
    void 가드가_실제로_미터를_훑는다() {
        exerciseAll();

        assertThat(ourMeters().count())
                .as("우리 미터가 하나도 등록되지 않았는데 위 두 테스트가 통과하면 "
                        + "가드는 아무것도 지키지 않는 것이다")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("⚠ 양성 대조 — 금지 태그를 넣으면 실제로 잡힌다")
    void 금지_태그를_넣으면_잡힌다() {
        // 🔴 PipelineMetrics 를 우회해 직접 등록한다. 「우회하면 잡힌다」를 보이는 것이 목적이고,
        //    PipelineMetrics 로는 애초에 이런 태그를 만들 방법이 없다(시그니처가 enum 만 받는다)
        registry.counter(MetricNames.PREFIX + "probe", "candidateId", "42").increment();

        List<String> forbidden = ourMeters()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .filter(MetricNames.FORBIDDEN_TAG_KEYS::contains)
                .toList();

        assertThat(forbidden)
                .as("이것이 비면 가드가 항상-초록이라는 뜻이다")
                .containsExactly("candidateId");
    }

    @Test
    @DisplayName("🔴 계측 실패가 업무 흐름으로 번지지 않는다 — NFR-2")
    void 계측_실패는_예외를_전파하지_않는다() {
        PipelineMetrics broken = new PipelineMetrics(new SimpleMeterRegistry() {
            @Override
            protected io.micrometer.core.instrument.Counter newCounter(Meter.Id id) {
                throw new IllegalStateException("레지스트리 고장");
            }
        });

        // 던지면 스캔 파이프라인이 메트릭 때문에 죽는다 — 본말전도다
        broken.safetyGate(SafetyClause.S5, GateOutcome.PASSED, null);
        broken.analysisOutcome(AnalysisOutcome.ANALYZED, 1);
        broken.llmCall(LlmCallSite.ANALYZE, LlmOutcome.SUCCEEDED, new LlmUsage(1, 1), 1);
    }

    @Test
    @DisplayName("0건은 기록하지 않는다 — 없는 결과로 시계열을 만들지 않는다")
    void 건수가_0이면_기록하지_않는다() {
        metrics.analysisOutcome(AnalysisOutcome.FAILED, 0);

        assertThat(ourMeters().count()).isZero();
    }

    private Stream<Meter> ourMeters() {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith(MetricNames.PREFIX));
    }

    /**
     * 태그 값으로 허용되는 어휘 — <b>전부 우리가 선언한 enum</b>과 고정 문자열이다.
     *
     * <p>새 어휘가 늘면 여기 등록해야 한다. 그 마찰이 의도다 — 태그 어휘가 조용히
     * 늘어나는 것을 막는 것이 이 가드의 목적이다.
     */
    private static Set<String> allowedTagValues() {
        List<String> values = new ArrayList<>();
        Stream.of(LlmCallSite.values()).map(Enum::name).forEach(values::add);
        Stream.of(LlmOutcome.values()).map(Enum::name).forEach(values::add);
        Stream.of(PipelineStage.values()).map(Enum::name).forEach(values::add);
        Stream.of(StageOutcome.values()).map(Enum::name).forEach(values::add);
        Stream.of(SafetyClause.values()).map(Enum::name).forEach(values::add);
        Stream.of(GateOutcome.values()).map(Enum::name).forEach(values::add);
        Stream.of(AnalysisOutcome.values()).map(Enum::name).forEach(values::add);
        Stream.of(ContributionNotAllowedException.Reason.values()).map(Enum::name)
                .forEach(values::add);
        // 고정 문자열 — 「사유 없음」과 토큰 방향
        values.addAll(Arrays.asList("NONE", "input", "output"));
        return values.stream().collect(Collectors.toUnmodifiableSet());
    }
}
