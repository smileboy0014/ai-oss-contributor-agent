package com.ossagent.support.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>「태그를 만드는 유일한 지점」을 문서가 아니라 코드로 강제한다</b> — S-4.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>{@link PipelineMetrics} javadoc 이 「태그를 만드는 유일한 지점」이라고 적어 뒀는데,
 * 초안에서는 <b>그 주장이 이미 거짓이었다</b> — {@code CandidateStatusGauge} 가
 * {@code MeterRegistry} 를 직접 받아 게이지를 등록하고 있었다. 값이 enum 이라 사고는
 * 나지 않았지만, <b>「한 곳에 가뒀다」는 전제가 깨진 채로 문서만 남으면 다음 사람이
 * 같은 패턴을 복제할 근거가 된다.</b>
 *
 * <p>규율을 문서로만 지키면 이런 일이 반복된다. {@code MdcLogPatternTest} 가
 * {@code MDC.put} 에 하는 것과 같은 방식으로 소스를 훑는다.
 *
 * <p>⚠️ 이 가드가 막는 것은 <b>태그 어휘가 흩어지는 것</b>이지 계측 자체가 아니다.
 * 새 미터가 필요하면 {@link PipelineMetrics} 에 메서드를 <b>추가</b>한다 — 그 마찰이 의도다.
 */
class MetricRegistryAccessRuleTest {

    /** {@code MeterRegistry} 를 직접 만져도 되는 곳 — 여기만이다. */
    private static final Set<String> ALLOWED = Set.of("PipelineMetrics.java");

    /** 주석 안의 {@code {@code MeterRegistry}} 언급은 잡지 않는다 — 실제 사용만 본다. */
    private static final Pattern REGISTRY_USE = Pattern.compile(
            "(?m)^(?!\\s*(?://|\\*|/\\*))\\s*.*\\bMeterRegistry\\b");

    @Test
    @DisplayName("🔴 MeterRegistry 를 만지는 곳은 PipelineMetrics 뿐이다 S4")
    void 레지스트리_접근이_한_곳으로_모여_있다_S4() throws IOException {
        List<String> violators;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            violators = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !ALLOWED.contains(path.getFileName().toString()))
                    .filter(MetricRegistryAccessRuleTest::usesRegistry)
                    .map(path -> path.getFileName().toString())
                    .toList();
        }

        assertThat(violators)
                .as("""
                        MeterRegistry 를 직접 만지는 곳이 PipelineMetrics 밖에 있다 — S-4.

                        태그 값이 코드 여기저기서 만들어지면 「이번엔 저장소 이름을 태그로」가
                        언젠가 들어오고, 카디널리티와 시크릿을 리뷰가 매번 다시 봐야 한다.
                        PipelineMetrics 에 메서드를 추가해 그쪽을 거치게 한다.""")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠ 양성 대조 — 허용된 곳에서는 실제로 쓰고 있다")
    void 허용된_곳은_실제로_레지스트리를_쓴다() {
        assertThat(usesRegistry(Path.of(
                "src/main/java/com/ossagent/support/observability/PipelineMetrics.java")))
                .as("여기서도 안 쓰면 이 가드는 아무것도 지키지 않는 것이다")
                .isTrue();
    }

    private static boolean usesRegistry(Path path) {
        try {
            return REGISTRY_USE.matcher(Files.readString(path, StandardCharsets.UTF_8)).find();
        } catch (IOException e) {
            throw new IllegalStateException("소스를 읽지 못했다: " + path, e);
        }
    }
}
