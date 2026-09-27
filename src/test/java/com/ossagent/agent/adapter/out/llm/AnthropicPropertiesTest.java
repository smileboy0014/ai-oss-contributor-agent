package com.ossagent.agent.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.agent.domain.LlmPricing;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;

class AnthropicPropertiesTest {

    private static final String FAKE_KEY = "sk-ant-" + "q".repeat(30);

    @Test
    @DisplayName("설정 객체를 찍어도 API 키가 나오지 않는다")
    void toString에_키가_실리지_않는다_S4() {
        AnthropicProperties properties = new AnthropicProperties(
                null, FAKE_KEY, null, 16000, Duration.ofSeconds(120), 2, Duration.ofMillis(500),
                null);

        assertThat(properties.toString())
                .as("record 의 기본 toString 은 모든 구성요소를 찍는다. "
                        + "설정 객체 하나를 로그에 남기는 것만으로 키가 통째로 나간다")
                .doesNotContain(FAKE_KEY)
                .contains("apiKey=");
    }

    /**
     * 🔴 <b>실제 {@code application.yml} 이 바인딩되는지 본다.</b>
     *
     * <p>이 테스트가 있는 이유 — {@code timeout-seconds: 120} 이라고 써 두었는데 필드명이
     * {@code timeout}(Duration)이라 <b>바인딩되지 않았다.</b> 생성자 기본값이 우연히 같아
     * 증상이 없었고, <b>운영자가 값을 낮춰도 아무 일도 일어나지 않는</b> 상태였다.
     *
     * <p>「기본값과 같아서 안 보이는 죽은 설정」은 단위 테스트로 잡히지 않는다.
     * 실제 yml 을 읽어 <b>기본값과 다른 값이 실제로 넘어오는지</b> 확인해야 한다.
     */
    @Test
    @DisplayName("application.yml 의 값이 실제로 바인딩된다")
    void 실제_yml_이_바인딩된다() throws IOException {
        AnthropicProperties bound = bindFromApplicationYml();

        assertThat(bound.timeout())
                .as("키 이름이 필드명과 어긋나면 조용히 기본값이 쓰인다")
                .isEqualTo(Duration.ofSeconds(120));
        assertThat(bound.model()).isEqualTo("claude-sonnet-5");
        assertThat(bound.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(bound.maxOutputTokens()).isEqualTo(16000);
        assertThat(bound.maxRetries())
                .as("전송 축 상한. agent.execution.max-retries(3) 와 다른 값이어야 축이 갈린 것이 보인다")
                .isEqualTo(2);
        assertThat(bound.retryBackoff()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("전송 재시도 상한에 천장이 있다")
    void 재시도_상한이_무한으로_커질_수_없다_S6() {
        assertThatThrownBy(() -> new AnthropicProperties(
                null, FAKE_KEY, null, 16000, Duration.ofSeconds(1), 50, Duration.ofMillis(1),
                null))
                .as("파이프라인 상한(3)과 곱해진다 — 설정만으로 조용히 돈을 태울 수 있으면 안 된다")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AnthropicProperties(
                null, FAKE_KEY, null, 16000, Duration.ofSeconds(1), -1, Duration.ofMillis(1),
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("🔴 단가가 없는 것이 기본이다 — 0 으로 채우지 않는다 (#71)")
    void 단가는_기본값이_없다() throws IOException {
        AnthropicProperties bound = bindFromApplicationYml();

        assertThat(bound.pricing())
                .as("""
                        기본 단가를 코드·설정에 박으면 「틀린 숫자를 자신 있게 보여준다」.
                        비어 있는 것이 의도다 — 그때는 비용 미터를 만들지 않는다.""")
                .isEmpty();
        assertThat(bound.pricingForModel()).isEmpty();
    }

    /**
     * 🔴 <b>죽은 설정이 아닌지 본다.</b> 위 {@code timeout-seconds} 사고와 같은 계열이다 —
     * 키 모양이 어긋나면 단가를 적어도 바인딩되지 않고, 증상은 <b>「비용이 조용히 안 찍힌다」</b>
     * 라서 눈에 띄지 않는다.
     */
    @Test
    @DisplayName("agent.llm.pricing.<model>.input/.output 이 실제로 바인딩된다 (#71)")
    void 단가_설정이_바인딩된다() {
        AnthropicProperties bound = bindPricing(Map.of(
                "agent.llm.model", "claude-sonnet-5",
                "agent.llm.pricing.claude-sonnet-5.input", "3.00",
                "agent.llm.pricing.claude-sonnet-5.output", "15.00"));

        assertThat(bound.pricingForModel())
                .as("키 모양이 어긋나면 값이 조용히 버려진다 — 이 단언이 그것을 잡는다")
                .contains(new LlmPricing(new BigDecimal("3.00"), new BigDecimal("15.00")));
    }

    @Test
    @DisplayName("단가표 키의 대소문자를 무시해 찾는다 (#71)")
    void 단가_조회가_대소문자를_무시한다() {
        AnthropicProperties bound = bindPricing(Map.of(
                "agent.llm.model", "Claude-Sonnet-5",
                "agent.llm.pricing.claude-sonnet-5.input", "3",
                "agent.llm.pricing.claude-sonnet-5.output", "15"));

        assertThat(bound.pricingForModel()).isPresent();
    }

    @Test
    @DisplayName("다른 모델의 단가를 대신 쓰지 않는다 (#71)")
    void 모델이_어긋나면_단가가_없다() {
        AnthropicProperties bound = bindPricing(Map.of(
                "agent.llm.model", "claude-sonnet-5",
                "agent.llm.pricing.claude-opus-5.input", "15",
                "agent.llm.pricing.claude-opus-5.output", "75"));

        assertThat(bound.pricingForModel())
                .as("가장 가까운 단가를 대신 쓰면 틀린 금액을 자신 있게 보여준다")
                .isEmpty();
        assertThat(bound.pricing())
                .as("표 자체는 남아야 한다 — 기동 로그가 「표는 있는데 키가 어긋났다」를 보여준다")
                .containsKey("claude-opus-5");
    }

    @Test
    @DisplayName("🔴 한쪽만 적힌 단가는 기동에서 거부한다 (#71)")
    void 단가를_한쪽만_적으면_거부한다() {
        assertThatThrownBy(() -> bindPricing(Map.of(
                "agent.llm.model", "claude-sonnet-5",
                "agent.llm.pricing.claude-sonnet-5.input", "3.00")))
                .as("output 이 비면 출력 토큰이 공짜인 장부가 만들어진다 — 운영 중에 조용히 "
                        + "반값이 찍히는 것보다 기동 실패가 낫다")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private static AnthropicProperties bindPricing(Map<String, Object> properties) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("test", properties));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("agent.llm", AnthropicProperties.class)
                .orElseThrow(() -> new AssertionError("agent.llm 블록을 찾지 못했다"));
    }

    private static AnthropicProperties bindFromApplicationYml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);

        // 플레이스홀더 리졸버를 준다 — 없으면 ${ANTHROPIC_MODEL:claude-sonnet-5} 가
        // 문자열 그대로 바인딩돼 「기본값이 먹는가」를 확인할 수 없다
        return new Binder(
                ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources))
                .bind("agent.llm", AnthropicProperties.class)
                .orElseThrow(() -> new AssertionError("agent.llm 블록을 찾지 못했다"));
    }
}
