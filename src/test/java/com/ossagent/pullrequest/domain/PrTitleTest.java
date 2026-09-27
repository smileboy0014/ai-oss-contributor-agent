package com.ossagent.pullrequest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.support.secret.TokenRedactor;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * PR 제목 — 재료가 <b>대상 저장소가 쓴 문자열</b>(이슈 제목)이라 우리가 안을 모른다.
 *
 * <p>여기서 보는 것은 셋이다 — <b>스크럽</b>(S-4) · <b>제어문자</b>(한 줄 렌더링) ·
 * <b>길이 상한</b>. 셋의 <b>순서</b>가 특히 중요하고, 그것이 아래 마지막 테스트다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PrTitleTest {

    /**
     * 🔴 <b>런타임에 조립한다.</b> 스크럽이 실제로 무는지를 보는 테스트라 <b>토큰의 문자셋이
     * 유의미</b>하다 — 패턴이 {@code gh[pousr]_[A-Za-z0-9]{20,}} 이라 밑줄이 섞인
     * 가짜 상수는 <b>애초에 걸리지 않는다.</b> 그것을 쓰면 「스크럽이 됐다」가 아니라
     * 「대상이 아니었다」를 재는 것이 된다 ({@code testing-philosophy.md} 「샘플의 대표성」).
     *
     * <p>소스에 리터럴로 두지 않는 것은 {@code secret-scan.sh} 때문이다.
     */
    private static final String FAKE_TOKEN = "ghp_" + "c".repeat(30);

    /** 잘린 토큰이 남았는지를 보려면 접두어만 따로 필요하다. 그 자체는 토큰 패턴이 아니다. */
    private static final String TOKEN_PREFIX = "ghp_";

    // ── forIssue ────────────────────────────────────────────────────────

    @Test
    void 이슈_번호를_괄호로_덧붙인다() {
        PrTitle title = PrTitle.forIssue(3412, "KafkaTemplate leaks producers on close");

        assertThat(title.value())
                .as("우리 커밋 컨벤션(feat(scope):)을 남의 저장소에 강요하지 않는다 — S-5")
                .isEqualTo("KafkaTemplate leaks producers on close (#3412)");
    }

    @Test
    void 이슈_제목이_비면_대체_문구를_쓴다() {
        for (String empty : new String[] {null, "", "   "}) {
            PrTitle title = PrTitle.forIssue(77, empty);

            assertThat(title.value())
                    .as("입력=%s — 빈 제목으로 PR 을 열면 메인테이너가 무엇인지 알 수 없다", empty)
                    .isEqualTo("Address issue #77 (#77)");
        }
    }

    @Test
    void 이슈_번호가_1_미만이면_거부한다() {
        for (int invalid : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThatThrownBy(() -> PrTitle.forIssue(invalid, "제목"))
                    .as("입력=%d", invalid)
                    .isInstanceOf(DraftPrException.class);
        }
    }

    // ── 제어문자 ────────────────────────────────────────────────────────

    @Test
    void 제어문자는_공백으로_접힌다() {
        // 🔴 거부목록이 아니라 여집합이다 — 「어떤 제어문자가 위험한가」를 세지 않는다.
        //    표본은 제목이 한 줄로 렌더링되는 자리에서 실제로 깨지는 것들이다
        PrTitle title = new PrTitle("앞\n중간\r\n뒤\t끝 더");

        assertThat(title.value())
                .as("줄바꿈이 들어가면 GitHub UI·메일 알림에서 뒤가 잘리거나 다른 필드처럼 보인다")
                .isEqualTo("앞 중간 뒤 끝 더")
                .doesNotContain("\n")
                .doesNotContain("\r")
                .doesNotContain("\t");
    }

    @Test
    void 제어문자뿐인_제목은_거부한다() {
        // ⚠ "\n\t" 로는 이 경로를 재지 못한다 — isBlank 가 먼저 잡아 「비었다」로 끝난다.
        //   보려는 것은 공백은 아닌데 제어문자뿐이라 blank 검사를 통과하고, 접기 뒤에야
        //   비는 입력이다. 그 경로가 없으면 빈 제목으로 PR 이 열린다
        // 🔴 문자를 코드로 만든다. 소스에 생 제어문자를 박으면 파일이 바이너리로 취급돼
        //    grep 계열 게이트(secret-scan.sh)가 그 파일을 통째로 건너뛴다 — #75 가 그 구멍이다
        String controlOnly = String.valueOf(new char[] {(char) 1, (char) 2, (char) 7});
        assertThat(controlOnly.isBlank())
                .as("표본이 blank 면 빈 문자열 검사를 두 번 재는 것이 된다 — 샘플의 대표성")
                .isFalse();

        assertThatThrownBy(() -> new PrTitle(controlOnly))
                .isInstanceOf(DraftPrException.class)
                .hasMessageContaining("제어문자");
    }

    @Test
    void 빈_제목은_거부한다() {
        assertThatThrownBy(() -> new PrTitle(null)).isInstanceOf(DraftPrException.class);
        assertThatThrownBy(() -> new PrTitle("")).isInstanceOf(DraftPrException.class);
        assertThatThrownBy(() -> new PrTitle("  \n ")).isInstanceOf(DraftPrException.class);
    }

    // ── 길이 ────────────────────────────────────────────────────────────

    @Test
    void 상한을_넘으면_말줄임으로_자른다() {
        PrTitle title = new PrTitle("가".repeat(PrTitle.MAX_LENGTH + 50));

        assertThat(title.value())
                .as("잘린 제목보다 우리가 자른 제목이 낫다")
                .hasSize(PrTitle.MAX_LENGTH)
                .endsWith("…");
    }

    @Test
    void 상한_이하는_그대로_둔다() {
        // 과차단 대조 — 위 테스트가 「항상 자른다」로 고장 나도 초록이 되지 않게 한다
        String exact = "나".repeat(PrTitle.MAX_LENGTH);

        assertThat(new PrTitle(exact).value()).isEqualTo(exact);
    }

    // ── S-4 : 순서 ──────────────────────────────────────────────────────

    @Test
    void 스크럽이_자르기보다_먼저_일어난다_S4() {
        // 🔴 대표성 — 표본이 정말 스크럽 규칙에 물리는 모양인가
        assertThat(TokenRedactor.redact(FAKE_TOKEN)).isEqualTo(TokenRedactor.MASK);

        // 🔴 상한 경계에 토큰을 걸친다. 자르기가 먼저면 토큰이 경계에서 반쪽만 남고,
        //    반쪽은 패턴에 걸리지 않아 「잘린 시크릿」이 그대로 나간다.
        //    앞부분 240자 + 공백 + 토큰 34자 = 275자 > 상한(256)
        String padded = "d".repeat(240) + " " + FAKE_TOKEN;
        assertThat(padded.length()).as("표본이 상한을 넘어야 자르기가 발동한다")
                .isGreaterThan(PrTitle.MAX_LENGTH);

        PrTitle title = new PrTitle(padded);

        assertThat(title.value())
                .as("자르기가 먼저였다면 ghp_ 로 시작하는 반쪽이 남는다")
                .doesNotContain(TOKEN_PREFIX)
                .doesNotContain(FAKE_TOKEN);
        // 🔴 모수 — 「사라졌다」가 「통째로 잘려 나갔다」가 아니라 「치환됐다」여야 한다
        assertThat(title.value())
                .as("치환 흔적이 없으면 순서를 잰 것이 아니라 잘림을 잰 것이다")
                .contains(TokenRedactor.MASK);
    }

    @Test
    void 이슈_제목의_토큰도_스크럽된다_S4() {
        PrTitle title = PrTitle.forIssue(12, "CI fails with " + FAKE_TOKEN);

        assertThat(title.value())
                .as("이슈 제목은 대상 저장소가 쓴 문자열이고 우리는 안을 모른다")
                .doesNotContain(FAKE_TOKEN)
                .contains(TokenRedactor.MASK)
                .endsWith("(#12)");
    }
}
