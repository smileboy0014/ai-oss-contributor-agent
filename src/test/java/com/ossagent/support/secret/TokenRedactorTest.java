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

    /**
     * 개인키 <b>본문</b>은 조립하지 않는다 — 가짜임이 한눈에 보이는 고정 문자열이다.
     * 헤더·푸터는 실물 그대로여야 패턴이 물리는지 증명된다. 이 줄들은 들여쓰기 때문에
     * {@code secret-scan.sh} 의 줄 앵커({@code ^-+BEGIN …})에 걸리지 않는다.
     */
    private static final String FAKE_KEY_BODY = "NOT-A-REAL-KEY-FOR-TESTS-ONLY";

    private static final String FAKE_PEM_BLOCK = String.join("\n",
            "-----BEGIN RSA PRIVATE KEY-----",
            FAKE_KEY_BODY,
            FAKE_KEY_BODY,
            "-----END RSA PRIVATE KEY-----");

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
    @DisplayName("텍스트 한가운데 낀 PEM 개인키 블록을 통째로 가린다")
    void PEM_개인키_블록을_통째로_가린다_S4() {
        // 대상 저장소 파일을 프롬프트에 싣는 현실 케이스 — 키가 파일 시작이 아니라 중간에 있다
        String fileContent = "# 배포 메모\n앞부분 설명\n" + FAKE_PEM_BLOCK + "\n뒷부분 설명\n";

        String redacted = TokenRedactor.redact(fileContent);

        assertThat(redacted)
                .as("헤더만 가리고 키 본문을 흘려보내면 스크럽한 의미가 없다")
                .doesNotContain(FAKE_KEY_BODY)
                .doesNotContain("BEGIN RSA PRIVATE KEY")
                .contains(TokenRedactor.MASK);
        assertThat(redacted)
                .as("키가 아닌 본문까지 삼키면 프롬프트가 망가진다")
                .contains("앞부분 설명")
                .contains("뒷부분 설명");
    }

    @Test
    @DisplayName("END 표시가 없는 개인키는 끝까지 가린다")
    void END_가_없는_개인키도_가린다_S4() {
        // 잘린 파일·앞부분만 인용된 로그. 어디까지가 키인지 알 수 없으므로 끝까지 가린다
        String truncated = "앞부분 설명\n-----BEGIN EC PRIVATE KEY-----\n" + FAKE_KEY_BODY;

        String redacted = TokenRedactor.redact(truncated);

        assertThat(redacted)
                .as("블록이 완결되지 않았다는 이유로 키 본문이 나가면 안 된다")
                .doesNotContain(FAKE_KEY_BODY)
                .contains(TokenRedactor.MASK)
                .startsWith("앞부분 설명");
    }

    @Test
    @DisplayName("스크럽은 멱등이다 — 다시 돌려도 같다")
    void 스크럽은_멱등이다_S4() {
        // 이슈 재수집처럼 같은 값이 여러 번 통과하는 경로가 있다. 이중 마스킹이 생기면 안 된다
        String text = String.join(" ", FAKE_CLASSIC_PAT, FAKE_AWS_KEY) + "\n" + FAKE_PEM_BLOCK;

        String once = TokenRedactor.redact(text);

        assertThat(TokenRedactor.redact(once))
                .as("두 번 스크럽한 결과가 달라지면 저장된 값과 새로 읽은 값이 어긋난다")
                .isEqualTo(once);
    }

    @Test
    @DisplayName("마스킹은 되돌릴 수 없다 — 원문 조각이 남지 않는다")
    void 마스킹은_되돌릴_수_없다_S4() {
        String redacted = TokenRedactor.redact("token=" + FAKE_CLASSIC_PAT);

        assertThat(redacted)
                .as("마스킹은 암호화가 아니다. 복원 단서를 남기면 유출 경로가 그대로 남는다")
                .isEqualTo("token=" + TokenRedactor.MASK)
                .doesNotContain("a".repeat(4));
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
