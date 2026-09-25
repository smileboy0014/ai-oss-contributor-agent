package com.ossagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.support.github.GitHubProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * 전송 설정이 <b>기본값에 맡겨지지 않았는지</b>.
 *
 * <p>JDK {@code HttpClient} 의 기본 연결 타임아웃은 「무제한」이다. 명시하지 않으면
 * 응답 없는 대외 호출이 스레드를 영원히 잡는다.
 *
 * <p>⚠ <b>한계</b> — 읽기 타임아웃은 {@code JdkClientHttpRequestFactory} 가 값을 되읽을 수단을
 * 주지 않아 여기서 확인하지 못한다. 내부 필드를 리플렉션으로 들여다보는 것은
 * 「구현 내부 필드에 의존」 금지에 걸리므로 하지 않는다
 * ({@code .claude/rules/conventions/testing-philosophy.md}). 설정이 전달되는 것은 코드로 보장하고,
 * 실제 동작 확인은 수동 검증 몫으로 남긴다.
 */
class GitHubClientConfigTest {

    @Test
    @DisplayName("연결 타임아웃이 설정값으로 걸린다")
    void 연결_타임아웃을_명시한다() {
        GitHubProperties properties = new GitHubProperties(null, "", Duration.ofSeconds(3),
                Duration.ofSeconds(7), 2, Duration.ofMillis(500), 100);

        HttpClient httpClient = GitHubClientConfig.httpClient(properties);

        assertThat(httpClient.connectTimeout())
                .as("기본값은 무제한이다. 비어 있으면 응답 없는 호출이 스레드를 영원히 잡는다")
                .contains(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("요청 팩토리를 만든다")
    void 요청_팩토리를_조립한다() {
        GitHubProperties properties = GitHubProperties.defaults();

        assertThat(GitHubClientConfig.requestFactory(properties)).isNotNull();
    }

    @Test
    @DisplayName("RestClient 를 빈으로 내보내지 않는다")
    void RestClient를_빈으로_노출하지_않는다_S1() {
        boolean exposesRestClient = Arrays.stream(GitHubClientConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .anyMatch(method -> RestClient.class.isAssignableFrom(method.getReturnType()));

        assertThat(exposesRestClient)
                .as("RestClient 빈이 열려 있으면 아무 컴포넌트나 restClient.post() 를 쓸 수 있고, "
                        + "읽기 전용 표면이 컨텍스트 수준에서 거짓이 된다 — S-1")
                .isFalse();
    }

    @Test
    @DisplayName("설정 기본값이 문서와 일치한다")
    void 기본값을_고정한다() {
        GitHubProperties defaults = GitHubProperties.defaults();

        assertThat(defaults.baseUrl()).isEqualTo("https://api.github.com");
        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.maxRetries()).isEqualTo(2);
        assertThat(defaults.retryBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(defaults.rateLimitThreshold()).isEqualTo(100);
        assertThat(defaults.hasToken()).isFalse();
    }
}
