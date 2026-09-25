package com.ossagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.support.github.GitHubProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 전송 설정이 <b>기본값에 맡겨지지 않았는지</b>.
 *
 * <p>JDK {@code HttpClient} 의 기본 연결 타임아웃은 「무제한」이다. 명시하지 않으면
 * 응답 없는 대외 호출이 스레드를 영원히 잡는다.
 *
 * <p><b>읽기 타임아웃과 리다이렉트 거부는 여기서 확인하지 않는다.</b>
 * {@code JdkClientHttpRequestFactory} 가 값을 되읽을 수단을 주지 않고, 내부 필드 리플렉션은
 * 「구현 내부 필드에 의존」 금지에 걸린다. 대신 <b>실제 소켓을 태워</b> 확인한다 —
 * {@link com.ossagent.support.github.GitHubTransportContractTest}.
 * 전송 계약은 WireMock 이 본다는 것이 Q-9 의 3계층 규약이다
 * ({@code .claude/rules/conventions/testing-philosophy.md}).
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
