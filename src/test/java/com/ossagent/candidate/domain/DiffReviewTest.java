package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 리뷰 결과 값 타입 — <b>S-4 의 수신 쪽 방어</b>가 여기 있다 (이슈 #20).
 *
 * <p>송신은 {@code PromptScrubber} 가 막지만, 리뷰가 diff 를 인용하면 대상 저장소의
 * 시크릿이 <b>우리 DB 로 복제</b>된다. 그 경로를 이 타입이 닫는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DiffReviewTest {

    /**
     * 🔴 <b>런타임에 조립한다.</b> 스크럽이 실제로 무는지를 보는 테스트라 <b>토큰의 문자셋이
     * 유의미</b>하다 — 패턴이 {@code gh[pousr]_[A-Za-z0-9]{20,}} 이라
     * {@code ghp_NOT_A_REAL_TOKEN_FOR_TESTS_ONLY} 는 <b>밑줄 때문에 걸리지 않는다.</b>
     * 그것을 쓰면 「스크럽이 됐다」가 아니라 「애초에 대상이 아니었다」를 재는 것이 된다
     * (testing-philosophy 「샘플의 대표성」).
     *
     * <p>소스에 리터럴로 두지 않는 것은 {@code secret-scan.sh} 때문이다 —
     * {@code TokenRedactorTest} 와 같은 관용구다.
     */
    private static final String FAKE_TOKEN = "ghp_" + "a".repeat(30);

    @Test
    void 요약은_스크럽을_거치지_않고는_들어올_수_없다_S4() {
        DiffReview review = new DiffReview(ReviewVerdict.PASS, true, true, null, true,
                "토큰이 보인다: " + FAKE_TOKEN, List.of());

        assertThat(review.summary())
                .as("모델이 diff 에 있던 토큰을 요약에 되뱉을 수 있다 — 그대로 DB 에 앉으면 "
                        + "#13 조회 API 와 PR 본문(#23)까지 나간다")
                .doesNotContain(FAKE_TOKEN);
    }

    @Test
    void 지적_항목도_하나하나_스크럽된다_S4() {
        // 🔴 요약만 막고 리스트를 빠뜨리기 쉬운 자리다. 한 항목만 새도 같은 유출이다
        DiffReview review = new DiffReview(ReviewVerdict.CHANGES_REQUESTED, false, true, null, true,
                "고칠 것이 있다", List.of("정상 지적", "여기에 " + FAKE_TOKEN + " 이 있다"));

        assertThat(review.findings()).noneMatch(finding -> finding.contains(FAKE_TOKEN));
    }

    @Test
    void 지적_목록은_밖에서_바꿀_수_없다() {
        DiffReview review = new DiffReview(ReviewVerdict.CHANGES_REQUESTED, false, true, null, true,
                "고칠 것이 있다", List.of("지적"));

        assertThatThrownBy(() -> review.findings().add("나중에 끼워 넣기"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 판정이_없으면_거부한다() {
        // 「모르는 값을 DB 에 넣지 않는다」 — IssueAnalysis 와 같은 규칙
        assertThatThrownBy(() -> new DiffReview(null, true, true, null, true, "요약", List.of()))
                .isInstanceOf(DiffReviewRejectedException.class);
    }

    @Test
    void 근거_없는_판정은_거부한다() {
        assertThatThrownBy(() ->
                new DiffReview(ReviewVerdict.UNDETERMINED, null, null, null, null, "  ", List.of()))
                .as("UNDETERMINED 도 「왜 판정 못 했는가」가 있어야 사람이 판단한다")
                .isInstanceOf(DiffReviewRejectedException.class);
    }

    @Test
    void PASS_인데_위반한_축이_있으면_거부한다() {
        // 모델이 자기 답을 뒤집은 것이다. 믿으면 위반이 통과로 적재된다
        assertThatThrownBy(() -> new DiffReview(ReviewVerdict.PASS, false, true, null, true,
                "통과", List.of()))
                .isInstanceOf(DiffReviewRejectedException.class);
    }

    @Test
    void PASS_인데_판정_불가_축이_있는_것은_모순이_아니다_S5() {
        // 🔴 규약을 모르면 관습 축을 세울 수 없다. 그것을 모순으로 치면
        //    정책 행이 없는 저장소가 영영 통과하지 못한다
        DiffReview review = new DiffReview(ReviewVerdict.PASS, true, true, null, true,
                "규약을 몰라 관습은 보지 못했다", List.of());

        assertThat(review.passed()).isTrue();
        assertThat(review.followsConventions()).isNull();
    }

    @Test
    void 고칠_대상_없는_변경_요구는_거부한다() {
        // 「고쳐라」라고만 하고 무엇을 고칠지 없으면 코딩 단계가 할 수 있는 것이 없다 —
        // 재시도 예산(Q-6)만 태우는 판정이다
        assertThatThrownBy(() -> new DiffReview(ReviewVerdict.CHANGES_REQUESTED,
                false, true, null, true, "뭔가 이상하다", List.of()))
                .isInstanceOf(DiffReviewRejectedException.class);
    }

    @Test
    void 판정_불가는_통과가_아니다_S5() {
        DiffReview review = new DiffReview(ReviewVerdict.UNDETERMINED, null, null, null, null,
                "판정할 근거가 없다", List.of());

        assertThat(review.passed())
                .as("🔴 verdict != CHANGES_REQUESTED 로 쓰면 여기서 조용히 통과로 접힌다")
                .isFalse();
        assertThat(review.isUndetermined()).isTrue();
    }

    @Test
    void 판정_불가는_재시도_대상이_아니다() {
        assertThat(ReviewVerdict.UNDETERMINED.warrantsRetry())
                .as("같은 입력에 같은 결과다 — 재시도하면 Q-6 예산만 태운다")
                .isFalse();
        assertThat(ReviewVerdict.CHANGES_REQUESTED.warrantsRetry())
                .as("고칠 대상이 있다 — 이쪽만 재시도가 의미 있다")
                .isTrue();
        assertThat(ReviewVerdict.PASS.warrantsRetry()).isFalse();
    }

    @Test
    void 거부_사유는_전부_재시도_대상이_아니다() {
        // 🔴 새 사유를 더하는 사람이 이 단언을 보게 한다. 호출자(#21)의 switch 에 맡기면
        //    새 값이 기본값으로 「재시도함」에 떨어진다
        for (DiffReviewRejectedException.Reason reason
                : DiffReviewRejectedException.Reason.values()) {
            assertThat(reason.retryable())
                    .as("%s 는 재시도해도 같은 결과여야 한다 — 아니면 이 단언을 고치기 전에 "
                            + "#21 의 분기를 먼저 본다", reason)
                    .isFalse();
        }
    }
}
