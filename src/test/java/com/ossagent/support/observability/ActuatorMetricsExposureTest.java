package com.ossagent.support.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ossagent.support.testing.AgentIntegrationTest;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 🔴 <b>메트릭 엔드포인트를 열면 실제로 무엇이 보이는가</b> — #25 FR-1 · S-4.
 *
 * <h2>왜 우리 미터만 보는 것으로 끝내지 않나</h2>
 *
 * <p>{@code metrics} 를 켜면 우리 미터만 나오는 것이 아니다. Boot 기본 미터
 * ({@code jvm.*}·{@code hikaricp.*}·{@code http.server.requests})가 함께 노출되고,
 * <b>그 태그는 우리가 정하지 않는다.</b> {@link PipelineMetrics} 의 「태그를 만드는
 * 유일한 지점」이라는 설계가 <b>기본 미터에는 적용되지 않는다.</b>
 *
 * <p>가장 위험한 것이 {@code http.server.requests} 의 {@code uri} 태그다.
 * 우리 API 는 {@code /api/repositories/{id}/scan} 처럼 경로 변수를 쓰는데,
 * 여기에 <b>실제 id 가 박히면</b> 카디널리티가 무한이 되고 식별자가 모니터링
 * 시스템으로 나간다 — S-4 와 같은 방향이다.
 *
 * <p>Boot 가 URI <b>템플릿</b>으로 태깅한다는 것은 문서로 알려져 있지만,
 * <b>우리 설정에서 실제로 그런지</b>는 확인해야 아는 것이다. 주장 대신 테스트로 고정한다.
 */
@AgentIntegrationTest
@AutoConfigureMockMvc
class ActuatorMetricsExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    @Test
    @DisplayName("🔴 http.server.requests 의 uri 태그가 템플릿이다 — 실제 id 가 아니다 S4")
    void 요청_메트릭이_식별자를_태그에_담지_않는다_S4() throws Exception {
        // 경로 변수에 눈에 띄는 값을 넣는다 — 태그에 그대로 박히면 바로 보인다
        mockMvc.perform(get("/api/repositories/{id}/scan", 987654321L));

        List<String> uriTags = registry.find("http.server.requests").meters().stream()
                .map(meter -> meter.getId().getTag("uri"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        assertThat(uriTags)
                .as("""
                        요청 메트릭이 하나도 없다 — 이 테스트가 0건을 검사하고 초록이 된 것이다.
                        MockMvc 가 Micrometer 필터를 태우지 않았을 수 있다.""")
                .isNotEmpty();

        assertThat(uriTags)
                .as("""
                        uri 태그에 실제 식별자가 박혔다 — S-4.
                        카디널리티가 무한이 되고, 식별자가 모니터링 시스템으로 계속 나간다.
                        Boot 의 URI 템플릿 태깅이 꺼졌거나 우리가 그것을 덮어썼는지 확인한다.""")
                .noneMatch(uri -> uri.contains("987654321"));
    }

    @Test
    @DisplayName("우리 미터가 actuator 에 노출된다 — FR-1")
    void 우리_미터가_노출된다() throws Exception {
        // 게이트를 한 번 태워 미터를 만든다(기동 시점에는 아직 없을 수 있다)
        registry.counter(MetricNames.SAFETY_GATE,
                MetricNames.TAG_CLAUSE, SafetyClause.S5.name(),
                MetricNames.TAG_OUTCOME, GateOutcome.PASSED.name(),
                MetricNames.TAG_REASON, "NONE").increment();

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.names").isArray());

        assertThat(ourMeterNames())
                .as("계측을 만들고 안 보여주면 이 이슈가 하는 일이 없다")
                .contains(MetricNames.SAFETY_GATE);
    }

    @Test
    @DisplayName("⚠ 후보 상태 게이지는 11개 상태를 모두 등록한다 — 0건과 「없음」을 가른다")
    void 상태_게이지가_전부_등록된다() {
        long gauges = registry.find(MetricNames.CANDIDATE_COUNT).gauges().size();

        assertThat(gauges)
                .as("DB 에 없는 상태를 등록하지 않으면 「0건」과 「그 상태가 존재하지 않음」이 "
                        + "대시보드에서 같아 보인다")
                .isEqualTo(com.ossagent.candidate.domain.CandidateStatus.values().length);
    }

    @Test
    @DisplayName("🔴 실제 레지스트리의 ossagent.* 전체가 우리 어휘만 쓴다 S4")
    void 실제_레지스트리의_우리_미터가_전부_우리_어휘다_S4() {
        // ⚠ MetricTagRuleTest 는 테스트가 직접 만든 SimpleMeterRegistry 를 훑는다.
        //   그래서 PipelineMetrics 를 거치지 않는 미터 — 특히 기동 시 등록되는
        //   candidate.count 게이지 — 를 한 번도 보지 못했다.
        //   여기서는 애플리케이션이 실제로 들고 있는 레지스트리를 본다
        List<String> unexpected = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith(MetricNames.PREFIX))
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> !AllowedTagValues.contains(tag.getValue()))
                .map(tag -> tag.getKey() + "=" + tag.getValue())
                .distinct()
                .toList();

        assertThat(unexpected)
                .as("실제 컨텍스트에 등록된 우리 미터에 통제 밖 태그 값이 있다 — S-4")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 실제 레지스트리의 우리 미터에 식별자 태그 키가 없다 S4")
    void 실제_레지스트리에_식별자_태그가_없다_S4() {
        List<String> forbidden = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith(MetricNames.PREFIX))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey)
                .filter(MetricNames.FORBIDDEN_TAG_KEYS::contains)
                .distinct()
                .toList();

        assertThat(forbidden).isEmpty();
    }

    private List<String> ourMeterNames() {
        return registry.getMeters().stream()
                .map(Meter::getId)
                .map(Meter.Id::getName)
                .filter(name -> name.startsWith(MetricNames.PREFIX))
                .distinct()
                .toList();
    }
}
