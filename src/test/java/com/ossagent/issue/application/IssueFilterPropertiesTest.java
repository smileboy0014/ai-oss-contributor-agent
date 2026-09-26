package com.ossagent.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 설정이 <b>실제로 바인딩되는지</b> 본다 — #9.
 *
 * <p>🔴 이 테스트가 있는 이유 — {@code agent.llm} 에서 {@code timeout-seconds: 120} 이라고
 * 써 두었는데 필드명이 {@code timeout} 이라 <b>바인딩되지 않았다</b>(#10). 생성자 기본값이
 * 우연히 같아 증상이 없었고, <b>운영자가 값을 바꿔도 아무 일도 일어나지 않는</b> 상태였다.
 *
 * <p>여기서는 그 위험이 더 크다 — 네 값이 전부 「기본값과 같아도 티가 안 나는」 임계다.
 */
class IssueFilterPropertiesTest {

    @Test
    @DisplayName("application.yml 의 값이 실제로 바인딩된다")
    void 실제_yml_이_바인딩된다() throws IOException {
        IssueFilterProperties bound = bindFromApplicationYml();

        assertThat(bound.minBodyLength()).isEqualTo(200);
        assertThat(bound.maxCommentCount()).isEqualTo(30);
        assertThat(bound.batchSize()).isEqualTo(200);
        assertThat(bound.maxBatchesPerRun()).isEqualTo(100);
    }

    @Test
    @DisplayName("값을 비워 두면 기본값이 선다")
    void 미설정이면_기본값이다() {
        IssueFilterProperties defaults = new IssueFilterProperties(null, null, null, null);

        assertThat(defaults.minBodyLength()).isEqualTo(200);
        assertThat(defaults.maxCommentCount()).isEqualTo(30);
        assertThat(defaults.batchSize()).isEqualTo(200);
        assertThat(defaults.maxBatchesPerRun()).isEqualTo(100);
    }

    @Test
    @DisplayName("배치 크기와 상한은 0 이하가 될 수 없다")
    void 배치_설정이_진행을_멈추게_할_수_없다() {
        assertThatThrownBy(() -> new IssueFilterProperties(200, 30, 0, 100))
                .as("배치 크기가 0 이면 한 건도 판정하지 못한 채 상한까지 루프를 돈다")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IssueFilterProperties(200, 30, 200, 0))
                .as("배치 상한이 0 이면 필터가 아무것도 하지 않는다 — 조용히 무력화된다")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IssueFilterProperties(-1, 30, 200, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("설정만으로 PASSED 를 죽일 수 없다")
    void 전건_보류가_되는_설정은_거부한다() {
        assertThatThrownBy(() -> new IssueFilterProperties(100_000, 30, 200, 100))
                .as("본문 길이 하한이 너무 크면 전건이 SHORT_BODY 로 보류되고, "
                        + "PASSED 가 도달 불가능해져 하류가 보류를 통과로 취급하게 된다")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IssueFilterProperties(200, 0, 200, 100))
                .as("코멘트 상한이 0 이면 코멘트가 하나라도 달린 이슈가 전부 보류다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static IssueFilterProperties bindFromApplicationYml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);

        return new Binder(
                ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources))
                .bind("issue.filter", IssueFilterProperties.class)
                .orElseThrow(() -> new AssertionError("issue.filter 블록을 찾지 못했다"));
    }
}
