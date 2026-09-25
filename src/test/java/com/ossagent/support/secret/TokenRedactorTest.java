package com.ossagent.support.secret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-4 — 토큰이 문자열을 타고 나가지 않는지.
 *
 * <p>⚠ 픽스처의 토큰 유사 문자열은 <b>런타임에 조립</b>한다. 소스에 리터럴로 두면
 * {@code .claude/scripts/secret-scan.sh} 가 커밋을 막고, 무엇보다 저장소에 토큰 모양의
 * 문자열을 남기지 않는 것이 원칙이다.
 */
class TokenRedactorTest {

    private static final String FAKE_CLASSIC_PAT = "ghp_" + "a".repeat(30);
    private static final String FAKE_OAUTH_TOKEN = "gho_" + "b".repeat(30);
    private static final String FAKE_FINE_GRAINED = "github" + "_pat_" + "c".repeat(30);
    private static final String FAKE_ANTHROPIC_KEY = "sk-" + "ant-" + "d".repeat(30);
    private static final String FAKE_AWS_KEY = "AKIA" + "EFGHIJKLMNOPQRST";
    private static final String FAKE_SLACK_TOKEN = "xox" + "b-" + "1".repeat(20);

    @Test
    @DisplayName("GitHub classic PAT 을 마스킹한다")
    void classic_PAT을_마스킹한다_S4() {
        String redacted = TokenRedactor.redact("요청 실패 token=" + FAKE_CLASSIC_PAT);

        assertThat(redacted)
                .as("원문 토큰이 남아 있으면 로그·예외로 그대로 유출된다")
                .doesNotContain(FAKE_CLASSIC_PAT)
                .contains(TokenRedactor.MASK);
    }

    @Test
    @DisplayName("우리가 쓰지 않는 토큰 종류도 전부 마스킹한다")
    void 모든_토큰_패턴을_마스킹한다_S4() {
        String text = String.join(" ", FAKE_OAUTH_TOKEN, FAKE_FINE_GRAINED, FAKE_ANTHROPIC_KEY,
                FAKE_AWS_KEY, FAKE_SLACK_TOKEN);

        String redacted = TokenRedactor.redact(text);

        assertThat(redacted)
                .as("실수로 주입된 다른 종류의 시크릿도 걸러야 한다")
                .doesNotContain(FAKE_OAUTH_TOKEN)
                .doesNotContain(FAKE_FINE_GRAINED)
                .doesNotContain(FAKE_ANTHROPIC_KEY)
                .doesNotContain(FAKE_AWS_KEY)
                .doesNotContain(FAKE_SLACK_TOKEN);
    }

    @Test
    @DisplayName("Authorization 헤더 형태로 실린 값은 토큰 형식과 무관하게 가린다")
    void Authorization_헤더_값을_가린다_S4() {
        String redacted = TokenRedactor.redact("Authorization: Bearer 사내프록시토큰값1234567890");

        assertThat(redacted)
                .as("토큰 형식을 모르는 자격증명도 헤더 자리면 가려야 한다")
                .doesNotContain("사내프록시토큰값1234567890")
                .contains("Bearer " + TokenRedactor.MASK);
    }

    @Test
    @DisplayName("토큰이 없는 메시지는 훼손하지 않는다")
    void 토큰이_없으면_원문을_유지한다() {
        String message = "GitHub 호출 실패 path=/repos/spring-projects/spring-kafka status=500";

        assertThat(TokenRedactor.redact(message))
                .as("정상 메시지를 훼손하면 스크럽을 끄고 싶어진다")
                .isEqualTo(message);
    }

    @Test
    @DisplayName("null 과 빈 문자열을 그대로 돌려준다")
    void null과_빈문자열을_견딘다() {
        assertThat(TokenRedactor.redact(null)).isNull();
        assertThat(TokenRedactor.redact("")).isEmpty();
    }

    @Test
    @DisplayName("mask 는 앞 4자만 남긴다")
    void mask는_앞_4자만_남긴다_S4() {
        assertThat(TokenRedactor.mask(FAKE_CLASSIC_PAT))
                .startsWith("ghp_")
                .doesNotContain("a".repeat(30))
                .endsWith(TokenRedactor.MASK);
        assertThat(TokenRedactor.mask("")).isEqualTo("(none)");
        assertThat(TokenRedactor.mask(null)).isEqualTo("(none)");
    }
}
