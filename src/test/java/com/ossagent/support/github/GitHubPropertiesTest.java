package com.ossagent.support.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GitHubPropertiesTest {

    private static final String FAKE_TOKEN = "ghp_" + "q".repeat(30);

    @Test
    @DisplayName("설정 객체를 찍어도 토큰이 나오지 않는다")
    void toString에_토큰이_실리지_않는다_S4() {
        GitHubProperties properties = new GitHubProperties(null, FAKE_TOKEN, null, null, 2, null, 100);

        assertThat(properties.toString())
                .as("record 의 기본 toString 은 모든 구성요소를 찍는다. "
                        + "설정 객체 하나를 로그에 남기는 것만으로 토큰이 통째로 나간다")
                .doesNotContain(FAKE_TOKEN)
                .contains("ghp_")
                .contains("token=");
    }

    @Test
    @DisplayName("자격증명도 값을 노출하지 않는다")
    void 자격증명의_toString에도_토큰이_없다_S4() {
        assertThat(new StaticTokenCredentials(FAKE_TOKEN).toString())
                .doesNotContain(FAKE_TOKEN)
                .contains("present=true");
    }

    @Test
    @DisplayName("토큰이 있으면 Bearer 헤더를 만든다")
    void Bearer_헤더를_만든다() {
        assertThat(new StaticTokenCredentials(FAKE_TOKEN).authorizationHeader())
                .contains("Bearer " + FAKE_TOKEN);
        assertThat(new StaticTokenCredentials("  ").authorizationHeader())
                .as("공백 토큰은 없는 것과 같다 — 빈 Bearer 를 보내면 401 로만 실패한다")
                .isEmpty();
    }

    @Test
    @DisplayName("빈 값은 기본값으로 채운다")
    void 빈_값을_기본값으로_채운다() {
        GitHubProperties properties = new GitHubProperties("  ", null, null, null, 0, null, 0);

        assertThat(properties.baseUrl()).isEqualTo("https://api.github.com");
        assertThat(properties.token()).isEmpty();
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("불가능한 설정을 기동 시점에 거부한다")
    void 잘못된_설정을_거부한다() {
        assertThatThrownBy(() -> new GitHubProperties(null, null, null, null, -1, null, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-retries");
        assertThatThrownBy(() -> new GitHubProperties(null, null, Duration.ZERO, null, 2, null, 100))
                .as("타임아웃 0 은 설정 실수다. 운영에서 무한 대기로 드러나기 전에 막는다")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect-timeout");
        assertThatThrownBy(() -> new GitHubProperties(null, null, null, Duration.ofSeconds(-1), 2, null, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-timeout");
    }
}
