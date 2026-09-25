package com.ossagent.agent.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.env.YamlPropertySourceLoader;
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
                null, FAKE_KEY, null, 16000, Duration.ofSeconds(120), 2, Duration.ofMillis(500));

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
                null, FAKE_KEY, null, 16000, Duration.ofSeconds(1), 50, Duration.ofMillis(1)))
                .as("파이프라인 상한(3)과 곱해진다 — 설정만으로 조용히 돈을 태울 수 있으면 안 된다")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AnthropicProperties(
                null, FAKE_KEY, null, 16000, Duration.ofSeconds(1), -1, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
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
