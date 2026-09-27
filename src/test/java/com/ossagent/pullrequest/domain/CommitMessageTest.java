package com.ossagent.pullrequest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.repository.domain.ContributionConstraints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-5 — 대상 저장소의 기여 규약을 우리 규약보다 우선한다.
 *
 * <p>우리 커밋 컨벤션은 <b>이 저장소 안에서만</b> 유효하다. 우리 형식을 남의 저장소에
 * 강요하는 코드가 있으면 반려다 — {@code commit-convention.md}.
 */
class CommitMessageTest {

    private static final CommitIdentity SIGNER = new CommitIdentity("Someone", "someone@example.com");

    @Test
    @DisplayName("우리 커밋 컨벤션이 산출물에 나타나지 않는다")
    void 우리_컨벤션을_강요하지_않는다_S5() {
        CommitMessage message = CommitMessage.from("Fix consumer rebalance", null,
                ContributionConstraints.unknown(), 1234, null);

        assertThat(message.text())
                .as("type(scope): 형식은 이 저장소 안에서만 유효하다 — S-5")
                .doesNotContain("feat(")
                .doesNotContain("fix(")
                .doesNotContain("chore(")
                .isEqualTo("Fix consumer rebalance");
    }

    @Test
    @DisplayName("규약이 sign-off 를 요구하면 트레일러를 붙인다")
    void signoff가_필수면_붙인다_S5() {
        CommitMessage message = CommitMessage.from("Fix it", "본문", constraints(false, true),
                1234, SIGNER);

        assertThat(message.text())
                .endsWith("Signed-off-by: Someone <someone@example.com>");
    }

    @Test
    @DisplayName("sign-off 가 필수인데 서명자가 없으면 조용히 건너뛰지 않고 던진다")
    void 서명자가_없으면_던진다_S5() {
        assertThatThrownBy(() -> CommitMessage.from("Fix it", null, constraints(false, true),
                1234, null))
                .as("서명 없는 커밋을 보내면 PR 이 DCO 검사에서 막히고, "
                        + "그것은 「규약을 읽고도 안 지킨」 모양이 된다")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sign-off");
    }

    @Test
    @DisplayName("규약이 이슈 참조를 요구하면 번호를 붙이되 닫기 키워드는 쓰지 않는다")
    void 이슈_참조는_붙이되_닫지_않는다_S5() {
        CommitMessage message = CommitMessage.from("Fix it", null, constraints(true, false),
                1234, null);

        assertThat(message.text()).contains("#1234");
        assertThat(message.text())
                .as("머지되면 남의 이슈가 닫히는 부수효과다. 규약이 요구하지 않는 한 우리가 정할 일이 아니다")
                .doesNotContain("Fixes")
                .doesNotContain("Closes")
                .doesNotContain("Resolves");
    }

    @Test
    @DisplayName("이슈 참조가 필수인데 번호가 없으면 던진다")
    void 이슈_번호가_없으면_던진다_S5() {
        assertThatThrownBy(() -> CommitMessage.from("Fix it", null, constraints(true, false),
                0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("규약이 아무것도 요구하지 않으면 아무것도 붙지 않는다")
    void 규약이_없으면_덧붙이지_않는다_S5() {
        CommitMessage message = CommitMessage.from("Fix it", "본문", ContributionConstraints.unknown(),
                1234, SIGNER);

        assertThat(message.text())
                .as("「우리가 아는 제약이 없다」이지 「아무거나 해도 된다」가 아니다 — 덜 하는 쪽이 안전하다")
                .isEqualTo("Fix it\n\n본문")
                .doesNotContain("#1234")
                .doesNotContain("Signed-off-by");
    }

    @Test
    @DisplayName("요약의 줄바꿈을 접는다 — 커밋 메시지의 첫 줄은 하나다")
    void 요약은_한_줄로_접힌다() {
        CommitMessage message = CommitMessage.from("Fix\nthe\tbug  now", null,
                ContributionConstraints.unknown(), 1, null);

        assertThat(message.text()).isEqualTo("Fix the bug now");
    }

    @Test
    @DisplayName("toString 이 본문을 노출하지 않는다")
    void toString은_본문을_노출하지_않는다_S4() {
        CommitMessage message = CommitMessage.from("비밀이 섞였을 수 있는 제목", null,
                ContributionConstraints.unknown(), 1, null);

        assertThat(message.toString())
                .as("이슈 제목·LLM 출력이 섞인 값이라 포맷 문자열에 들어가면 로그 인젝션 경로가 된다")
                .doesNotContain("비밀이 섞였을 수 있는 제목")
                .contains("length=");
    }

    private static ContributionConstraints constraints(boolean issueRef, boolean signoff) {
        return new ContributionConstraints(null, null, null, false, issueRef, signoff);
    }
}
