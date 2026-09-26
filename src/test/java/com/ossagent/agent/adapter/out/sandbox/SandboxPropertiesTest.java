package com.ossagent.agent.adapter.out.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.time.Duration;
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
 * 실제 {@code application.yml} 이 바인딩되는지 본다 — #17.
 *
 * <h2>🔴 {@code Duration} 함정이 두 방향이다</h2>
 *
 * <p>#10 에서는 키 이름이 필드명과 어긋나 <b>아예 바인딩되지 않았고</b>, 생성자 기본값이
 * 우연히 같아 증상이 없었다. 운영자가 값을 낮춰도 아무 일도 일어나지 않는 상태였다.
 *
 * <p>여기는 반대다 — {@code SANDBOX_TIMEOUT_SECONDS=1800} 을 단위 없이 {@code Duration} 에
 * 넣으면 Spring 이 <b>밀리초</b>로 읽어 <b>1.8초</b>가 된다. 모든 샌드박스 실행이 즉시
 * 타임아웃하는데, 증상은 「테스트가 자꾸 실패한다」로만 보여 원인을 짚기 어렵다.
 *
 * <p>그래서 <b>「바인딩됐다」가 아니라 「값이 30분이다」를 단언</b>한다.
 */
class SandboxPropertiesTest {

    @Test
    @DisplayName("타임아웃이 밀리초로 오해석되지 않는다")
    void 초_단위가_그대로_바인딩된다() throws IOException {
        SandboxProperties bound = bindFromApplicationYml();

        assertThat(bound.timeout())
                .as("1800 이 밀리초로 읽히면 1.8초가 되어 모든 실행이 즉시 타임아웃한다")
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(bound.warmTimeout())
                .as("워밍은 실행보다 짧아야 한다")
                .isEqualTo(Duration.ofMinutes(20))
                .isLessThan(bound.timeout());
        assertThat(bound.logTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("메모리 상한에 단위가 실제로 먹는다")
    void 메모리_상한이_바이트로_환산된다() throws IOException {
        assertThat(bindFromApplicationYml().memoryLimit().toBytes())
                .as("단위 없는 값(4g)은 DataSize 에 바인딩되지 않는다 — .env.example 도 4GB 로 맞췄다")
                .isEqualTo(4L * 1024 * 1024 * 1024);
    }

    @Test
    @DisplayName("코어 수가 Docker quota 로 환산된다")
    void CPU_코어가_quota_로_환산된다() throws IOException {
        SandboxProperties bound = bindFromApplicationYml();

        assertThat(bound.cpuLimit()).isEqualTo(2.0);
        assertThat(bound.executeLimits().cpuQuota())
                .as("SANDBOX_CPU_LIMIT 은 코어 단위다. quota/period 환산을 운영자에게 시키지 않는다")
                .isEqualTo(200_000L);
        assertThat(bound.executeLimits().cpuPeriod()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("네트워크는 설정 키로 존재하지 않는다")
    void sandbox_network_라는_키가_없다_S3() throws IOException {
        SandboxProperties bound = bindFromApplicationYml();

        assertThat(bound.warmNetwork())
                .as("""
                        네트워크는 명령 타입이 정한다. 설정 키가 있으면 실행 단계 격리가
                        배포 설정 한 줄로 꺼진다 — SANDBOX_NETWORK 를 없앤 이유다.
                        남은 것은 워밍 전용 네트워크 「이름」뿐이고 그것도 host·container 를 거부한다.""")
                .isEqualTo("oss-agent-warm");

        assertThat(rawKeys())
                .as("sandbox.network 키가 되살아나면 여기서 잡는다")
                .noneMatch(key -> key.equals("sandbox.network"));
    }

    @Test
    @DisplayName("상한이 전부 실린다")
    void 상한이_빠짐없이_바인딩된다_S3() throws IOException {
        SandboxProperties bound = bindFromApplicationYml();

        assertThat(bound.pidsLimit()).isEqualTo(512L);
        assertThat(bound.maxOutputChars()).isEqualTo(200_000);
        assertThat(bound.dockerApiVersion()).isEqualTo("1.44");
        assertThat(bound.defaultImage()).isEqualTo("eclipse-temurin:21-jdk");
        assertThat(bound.workspaceRoot())
                .as("기동 시점에 절대경로로 확정된다 — 상대경로는 작업 디렉토리에 따라 달라진다")
                .isAbsolute();
    }

    private static SandboxProperties bindFromApplicationYml() throws IOException {
        MutablePropertySources sources = propertySources();
        return new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources))
                .bind("sandbox", SandboxProperties.class)
                .orElseThrow(() -> new AssertionError("sandbox 블록을 찾지 못했다"));
    }

    /**
     * ⚠ <b>모든 YAML 문서를 훑는다.</b> 첫 문서만 보면 {@code ---} 로 나뉜 뒤
     * {@code sandbox} 블록이 두 번째로 옮겨갔을 때 위 회귀 가드가 <b>조용히 무력해진다</b>.
     */
    @SuppressWarnings("unchecked")
    private static List<String> rawKeys() throws IOException {
        List<String> keys = new ArrayList<>();
        for (PropertySource<?> source : propertySources()) {
            keys.addAll(((java.util.Map<String, Object>) source.getSource()).keySet());
        }
        return List.copyOf(keys);
    }

    private static MutablePropertySources propertySources() throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        return sources;
    }
}
