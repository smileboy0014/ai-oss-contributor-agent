package com.ossagent.support.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmPricing;
import com.ossagent.agent.domain.LlmUsage;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
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

    /** 100만 토큰당 USD — 실제 공시 단가의 모양이다. 값 자체는 테스트의 관심이 아니다. */
    private static final LlmPricing SONNET =
            new LlmPricing(new BigDecimal("3.00"), new BigDecimal("15.00"));

    private SimpleMeterRegistry registry;
    private PipelineMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PipelineMetrics(registry);
    }

    /** 이 PR 이 만드는 모든 계측을 한 번씩 태운다 — 가드가 0건을 검사하고 초록이 되지 않게. */
    private void exerciseAll() {
        metrics.llmCall(LlmCallSite.ANALYZE, LlmOutcome.SUCCEEDED, new LlmUsage(10, 20), 1,
                SONNET);
        metrics.llmCall(LlmCallSite.POLICY, LlmOutcome.FAILED, null, 2, SONNET);
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

        List<String> unexpected = ourMeters()
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> !AllowedTagValues.contains(tag.getValue()))
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
        broken.llmCall(LlmCallSite.ANALYZE, LlmOutcome.SUCCEEDED, new LlmUsage(1, 1), 1,
                SONNET);
    }

    @Test
    @DisplayName("0건은 기록하지 않는다 — 없는 결과로 시계열을 만들지 않는다")
    void 건수가_0이면_기록하지_않는다() {
        metrics.analysisOutcome(AnalysisOutcome.FAILED, 0);

        assertThat(ourMeters().count()).isZero();
    }


    @Test
    @DisplayName("🔴 단가가 없으면 비용 미터를 만들지 않는다 — 0 은 「공짜」로 읽힌다 (#71)")
    void 단가가_없으면_비용_미터가_없다() {
        metrics.llmCall(LlmCallSite.ANALYZE, LlmOutcome.SUCCEEDED, new LlmUsage(10, 20), 1, null);

        assertThat(registry.find(MetricNames.LLM_COST).counter())
                .as("""
                        단가가 없을 때 0 을 기록하면 「비용이 0 이다」로 읽힌다 — 그것은
                        「모른다」와 전혀 다른 말이다. 미터가 아예 없어야 대시보드에서
                        「측정하지 않음」으로 보인다.""")
                .isNull();
        assertThat(registry.find(MetricNames.LLM_TOKENS).counters())
                .as("토큰은 단가와 무관하게 센다 — 나중에 단가를 알면 곱하면 된다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("사용량을 모르면 비용도 만들지 않는다 (#71)")
    void 사용량을_모르면_비용이_없다() {
        // 타임아웃으로 끊긴 호출이다. 모델은 토큰을 생성했지만 얼마인지 모른다 —
        // 0 으로 적으면 가장 비싼 경로가 장부에서 「공짜」가 된다
        metrics.llmCall(LlmCallSite.CODE, LlmOutcome.FAILED, null, 1, SONNET);

        assertThat(registry.find(MetricNames.LLM_COST).counter()).isNull();
        assertThat(registry.find(MetricNames.LLM_CALLS).counter()).isNotNull();
    }

    @Test
    @DisplayName("🔴 비용 기록이 터져도 나머지 계측이 남는다 (#71)")
    void 비용이_터져도_시도_번호는_기록된다() {
        // 🔴 「비용을 못 셌다」가 「시도 번호도 못 셌다」가 되면 안 된다.
        //    한 record() 람다에 묶여 있으면 실제로 그렇게 된다 — 뒤의 기록이 통째로 날아가고,
        //    증상은 WARN 한 줄이라 사라진 시계열이 대시보드에서 0 과 구분되지 않는다
        var 비용만_고장난_레지스트리 = new SimpleMeterRegistry() {
            @Override
            protected io.micrometer.core.instrument.Counter newCounter(Meter.Id id) {
                if (MetricNames.LLM_COST.equals(id.getName())) {
                    throw new IllegalStateException("비용 미터 등록 고장");
                }
                return super.newCounter(id);
            }
        };

        new PipelineMetrics(비용만_고장난_레지스트리).llmCall(
                LlmCallSite.ANALYZE, LlmOutcome.SUCCEEDED, new LlmUsage(10, 20), 2, SONNET);

        assertThat(비용만_고장난_레지스트리.find(MetricNames.LLM_CALL_ATTEMPT).summary())
                .as("비용 기록의 실패가 삼켜야 하는 것은 「그 계측 하나」다 — "
                        + "같은 람다에 있던 나머지 전부가 아니다")
                .isNotNull();
        assertThat(비용만_고장난_레지스트리.find(MetricNames.LLM_CALLS).counter()).isNotNull();
        assertThat(비용만_고장난_레지스트리.find(MetricNames.LLM_COST).counter())
                .as("정작 실패한 비용은 없어야 한다 — 없는 값을 0 으로 꾸미지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("🔴 비용은 통화를 태그가 아니라 baseUnit 으로 싣는다 (#71)")
    void 비용_미터가_통화를_단위로_담는다() {
        metrics.llmCall(LlmCallSite.ANALYZE, LlmOutcome.SUCCEEDED,
                new LlmUsage(1_000_000, 1_000_000), 1, SONNET);

        var cost = registry.find(MetricNames.LLM_COST).counter();
        assertThat(cost).isNotNull();
        assertThat(cost.count()).isEqualTo(18.0);
        assertThat(cost.getId().getBaseUnit())
                .as("값이 하나뿐인 태그를 모든 시계열에 붙일 이유가 없다")
                .isEqualTo(MetricNames.CURRENCY);
        assertThat(cost.getId().getTags())
                .as("모델은 설정 문자열이라 우리가 통제하는 어휘가 아니다 — 태그로 달지 않는다")
                .singleElement()
                .extracting(Tag::getKey)
                .isEqualTo(MetricNames.TAG_CALL_SITE);
    }

    @Test
    @DisplayName("🔴 절단된 호출도 비용을 센다 — 응답을 받았으므로 사용량을 안다 (#71)")
    void 실패해도_아는_사용량은_비용에_들어간다() {
        metrics.llmCall(LlmCallSite.CODE, LlmOutcome.FAILED, new LlmUsage(1_000_000, 0), 1,
                SONNET);

        assertThat(registry.find(MetricNames.LLM_COST).counter().count())
                .as("실패를 빼면 가장 비싼 경로가 장부에서 사라진다")
                .isEqualTo(3.0);
    }

    private Stream<Meter> ourMeters() {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith(MetricNames.PREFIX));
    }
}
