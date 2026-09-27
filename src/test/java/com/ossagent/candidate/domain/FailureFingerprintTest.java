package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 실패 지문 — #21 · FR-5 · S-4.
 *
 * <h2>🕳 이 테스트가 증명하지 <b>않는</b> 것</h2>
 *
 * <p>「같은 오류면 같은 지문」은 여기서 초록이지만, <b>실물에서는 성립하지 않을 수 있다</b> —
 * 빌드 출력에 타임스탬프·소요 시간·절대 경로가 섞이면 같은 오류라도 문자열이 달라진다.
 * {@link FailureFingerprint} javadoc 이 그 한계를 맨 앞에 적어 뒀다.
 *
 * <p>여기서 보는 것은 <b>장치가 동작하는가</b>이지 <b>실물에서 무는가</b>가 아니다.
 * 둘을 헷갈리면 「테스트가 초록이니 조기 중단이 돈다」로 읽힌다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FailureFingerprintTest {

    @Test
    void 같은_피드백은_같은_지문이다() {
        CodingFeedback a = feedback(CodingFeedback.Kind.TEST, "A 가 깨졌다");
        CodingFeedback b = feedback(CodingFeedback.Kind.TEST, "A 가 깨졌다");

        assertThat(FailureFingerprint.of(a)).isEqualTo(FailureFingerprint.of(b));
    }

    @Test
    void 내용이_다르면_다른_지문이다() {
        assertThat(FailureFingerprint.of(feedback(CodingFeedback.Kind.TEST, "A 가 깨졌다")))
                .isNotEqualTo(FailureFingerprint.of(
                        feedback(CodingFeedback.Kind.TEST, "B 가 깨졌다")));
    }

    /**
     * 🔴 <b>종류가 다르면 다른 실패다.</b>
     *
     * <p>종류를 재료에서 빼면 지적 내용이 우연히 같을 때 <b>서로 다른 실패가 같은 지문</b>이
     * 되고, 그러면 <b>정상 재시도가 조기 중단된다</b> — 조용히 한 바퀴를 잃는다.
     */
    @Test
    void 종류가_다르면_다른_지문이다() {
        assertThat(FailureFingerprint.of(feedback(CodingFeedback.Kind.COMPILE, "같은 문구")))
                .isNotEqualTo(FailureFingerprint.of(
                        feedback(CodingFeedback.Kind.REVIEW, "같은 문구")));
    }

    /**
     * 🔴 구분자가 없으면 <b>이어 붙인 결과가 같아진다.</b>
     *
     * <p>{@code ["ab","c"]} 와 {@code ["a","bc"]} 가 같은 지문이 되면 서로 다른 지적이
     * 같은 실패로 뭉개진다.
     */
    @Test
    void 항목_경계가_지문에_반영된다() {
        CodingFeedback split = new CodingFeedback(CodingFeedback.Kind.REVIEW, List.of("ab", "c"));
        CodingFeedback other = new CodingFeedback(CodingFeedback.Kind.REVIEW, List.of("a", "bc"));

        assertThat(FailureFingerprint.of(split)).isNotEqualTo(FailureFingerprint.of(other));
    }

    /**
     * 🔴 <b>원문을 들고 있지 않다</b> — S-4.
     *
     * <p>재료는 빌드 출력과 모델 응답이다. 지문이 원문을 들고 있으면 {@code toString} ·
     * 로그 · 예외 메시지로 <b>샐 자리가 하나 더</b> 생긴다.
     */
    @Test
    void 지문에_원문이_남지_않는다_S4() {
        String secretish = "Authorization: Bearer ghp_LOOKS_LIKE_A_TOKEN_BUT_IS_NOT";
        FailureFingerprint fingerprint =
                FailureFingerprint.of(feedback(CodingFeedback.Kind.TEST, secretish));

        assertThat(fingerprint.toString())
                .as("해시만 남아야 한다 — 실수로 로그에 실려도 빌드 출력이 나가지 않는다")
                .doesNotContain("ghp_")
                .doesNotContain("Bearer")
                .hasSize(12);
        assertThat(fingerprint.value()).matches("[0-9a-f]{12}");
    }

    /**
     * 🔴 <b>피드백 자신도 내용을 찍지 않는다.</b>
     *
     * <p>지문만 막고 피드백이 찍히면 같은 유출이다 — 둘은 같은 경로에 있다.
     */
    @Test
    void 피드백도_내용을_찍지_않는다_S4() {
        CodingFeedback f = feedback(CodingFeedback.Kind.TEST, "비밀이 섞인 빌드 출력");

        assertThat(f.toString())
                .doesNotContain("비밀이 섞인 빌드 출력")
                .contains("kind=TEST")
                .contains("points=1");
    }

    private static CodingFeedback feedback(CodingFeedback.Kind kind, String point) {
        return new CodingFeedback(kind, List.of(point));
    }
}
