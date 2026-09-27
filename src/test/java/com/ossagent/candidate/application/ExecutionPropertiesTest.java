package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 파이프라인 실행 설정 — #21 의 개명(Q-6 잔여) · S-6.
 *
 * <h2>🔴 개명은 <b>이름만</b> 바꾼 것이다</h2>
 *
 * <p>키가 {@code max-retries} 에서 {@code max-attempts} 로 바뀌었고, <b>값도 비교도
 * 그대로다.</b> Q-6 이 경고한 위험은 개명이 아니라 <b>비교를 함께 고치는 것</b>이었다 —
 * 「retries 니 3회 재시도 = 총 4회겠지」로 읽으면 곱셈 예산(3 × (1+2) = 대외 호출 9회)이
 * 함께 무효가 된다.
 *
 * <h2>🔴 이 테스트가 잡는 진짜 회귀 — 키와 코드가 어긋나는 것</h2>
 *
 * <p>{@code @ConfigurationProperties} 바인딩은 <b>키가 없으면 조용히 기본값</b>(여기서는
 * {@code 0})으로 간다. {@code application.yml} 만 고치고 record 를 안 고치면(또는 반대면)
 * <b>컴파일은 되고 테스트도 대부분 초록</b>인 채로 상한이 사라진다.
 *
 * <p>그래서 <b>실제 {@code application.yml} 을 바인딩한다.</b> record 를 직접 생성하는
 * 테스트는 그 어긋남을 영원히 못 본다.
 *
 * <p>⚠️ <b>「설정이 도메인 상한을 넘지 않는가」는 여기서 보지 않는다.</b>
 * {@code MAX_ALLOWED_ATTEMPTS} 가 package-private 이라 이 패키지에서 보이지 않고,
 * 예외 <b>메시지 문자열</b>로 되짚는 것은 거부목록이다. 그 교차 검증은
 * {@code ContributionCandidateTest}(같은 패키지)가 한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ExecutionPropertiesTest {

    @Test
    @DisplayName("🔴 application.yml 의 키가 record 와 맞는다 — 어긋나면 상한이 조용히 사라진다")
    void yml_의_키가_코드와_맞는다_S6() throws IOException {
        ExecutionProperties properties = bindFromApplicationYml();

        assertThat(properties.maxAttempts())
                .as("agent.execution.max-attempts 가 바인딩되지 않으면 0 이 된다. "
                        + "0 은 무한이 아니라 즉시 FAILED 라 증상이 「후보가 다 죽는다」다")
                .isEqualTo(3);
        assertThat(properties.timeoutSeconds()).isEqualTo(1800);
    }

    /**
     * ⚠️ <b>아래쪽만 막는 것으로는 부족하다.</b> 그래도 여기서는 아래쪽만 본다 —
     * 위쪽 경계는 도메인이 보고(위 테스트), 프로퍼티는 「값이 말이 되는가」만 본다.
     *
     * <p>🔴 {@code 0} 은 무한이 아니라 <b>최강 제약</b>이다({@code attempt >= 0} 이 항상
     * 참이라 즉시 {@code FAILED}). 그래서 「0 이면 무제한」으로 읽는 경로를 만들지 않는다.
     */
    @Test
    void 시도_수가_1_미만이면_기동을_거부한다_S6() {
        assertThatThrownBy(() -> new ExecutionProperties(0, 1800))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts");
    }

    @Test
    void 타임아웃이_1_미만이면_기동을_거부한다() {
        assertThatThrownBy(() -> new ExecutionProperties(3, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout-seconds");
    }

    /**
     * 🔴 <b>개명의 흔적이 남지 않았는지</b> 본다.
     *
     * <p>{@code agent.execution.max-retries} 가 {@code application.yml} 에 남아 있으면
     * 바인딩되지 않은 채 <b>조용히 무시</b>된다 — 「고쳤다고 생각했는데 옛 키가 살아 있는」
     * 상태가 가장 나쁘다.
     */
    @Test
    void 옛_키가_yml_에_남아_있지_않다() throws IOException {
        String yml = new String(new ClassPathResource("application.yml")
                .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(yml)
                .as("execution 축의 옛 키가 남으면 바인딩되지 않은 채 조용히 무시된다. "
                        + "⚠ github.max-retries · agent.llm.max-retries 는 다른 축이고 "
                        + "이름과 의미가 맞으므로 그대로 둔다")
                .doesNotContain("agent.execution.max-retries")
                // yml 의 블록 표기까지 본다 — 「execution:」 아래의 「max-retries:」
                .doesNotContain("max-retries: 3");
    }

    // ── 픽스처 ─────────────────────────────────────────────────────────────

    private static ExecutionProperties bindFromApplicationYml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);

        return new Binder(
                ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources))
                .bind("agent.execution", ExecutionProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "agent.execution 블록을 찾지 못했다 — 키를 지웠거나 이름이 어긋났다"));
    }
}
