package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.support.secret.TokenRedactor;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 분석 결과 값 타입 — <b>스키마 게이트</b>이자 <b>스크럽 게이트</b>다.
 *
 * <p>이 타입을 통과했다는 것이 「쓸 수 있는 판정」이라는 뜻이어야 한다. 느슨하면
 * {@code difficulty} 가 엉뚱한 후보, {@code confidence} 가 범위 밖인 후보가 DB 에 앉는다.
 */
class IssueAnalysisTest {

    /** 소스에 토큰 패턴 리터럴을 두지 않는다 — {@code secret-scan.sh} 가 커밋을 막는다. */
    private static final String FAKE_TOKEN = "ghp_" + "a".repeat(36);

    @Test
    @DisplayName("정상 판정은 그대로 통과하고 confidence 는 scale 2 로 고정된다")
    void 정상_판정은_통과한다() {
        IssueAnalysis analysis = IssueAnalysisFixtures.withConfidence("0.871");

        assertThat(analysis.confidence())
                .as("컬럼이 NUMERIC(3,2) 다 — 적재 전에 맞춰 둔다")
                .isEqualByComparingTo("0.87");
        assertThat(analysis.confidence().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 summary 의 토큰은 생성 시점에 스크럽된다 — 우회 경로가 없다 S4")
    void summary_는_스크럽을_거쳐야만_만들어진다_S4() {
        IssueAnalysis analysis =
                IssueAnalysisFixtures.withSummary("재현하려면 " + FAKE_TOKEN + " 이 필요하다");

        assertThat(analysis.summary())
                .as("모델이 프롬프트의 토큰을 되뱉을 수 있다 — 적재 측 1차 방어가 여기다")
                .doesNotContain(FAKE_TOKEN)
                .contains(TokenRedactor.MASK);
    }

    @Test
    @DisplayName("toString 이 요약 본문을 노출하지 않는다")
    void toString_이_본문을_흘리지_않는다_S4() {
        IssueAnalysis analysis = IssueAnalysisFixtures.withSummary("대상 저장소에서 온 텍스트");

        assertThat(analysis.toString())
                .as("로그에 그대로 찍히는 경로다 — 인젝션이기도 하다")
                .doesNotContain("대상 저장소에서 온 텍스트")
                .contains("summarySize=");
    }

    @Test
    @DisplayName("difficulty 가 없으면 거부한다")
    void difficulty_가_없으면_거부한다() {
        assertThatThrownBy(() -> IssueAnalysisFixtures.builder().difficulty(null).build())
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("difficulty");
    }

    @Test
    @DisplayName("confidence 가 0.00~1.00 밖이면 거부한다 — 적재 시점이 아니라 여기서")
    void confidence_범위_밖이면_거부한다() {
        assertThatThrownBy(() -> IssueAnalysisFixtures.withConfidence("1.5"))
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("0.00~1.00");

        assertThatThrownBy(() -> IssueAnalysisFixtures.withConfidence("-0.1"))
                .isInstanceOf(AnalysisRejectedException.class);
    }

    @Test
    @DisplayName("경계값 0.00 과 1.00 은 허용한다")
    void 경계값은_허용한다() {
        assertThat(IssueAnalysisFixtures.withConfidence("0.00").confidence())
                .isEqualByComparingTo("0.00");
        assertThat(IssueAnalysisFixtures.withConfidence("1.00").confidence())
                .isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("confidence 가 없으면 거부한다")
    void confidence_가_없으면_거부한다() {
        assertThatThrownBy(() -> IssueAnalysisFixtures.builder().confidence(null).build())
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    @DisplayName("추정치가 음수면 거부한다")
    void 추정치가_음수면_거부한다() {
        assertThatThrownBy(() -> IssueAnalysisFixtures.builder().estimatedFiles(-1).build())
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("음수");
    }

    @Test
    @DisplayName("category·summary 가 비어 있으면 거부한다")
    void 필수_텍스트가_비면_거부한다() {
        assertThatThrownBy(() -> IssueAnalysisFixtures.builder().category("  ").build())
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("category");

        assertThatThrownBy(() -> IssueAnalysisFixtures.withSummary(null))
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("summary");
    }

    @Test
    @DisplayName("category 가 컬럼 길이를 넘으면 거부한다 — 적재에서 터지지 않게")
    void category_가_너무_길면_거부한다() {
        assertThatThrownBy(() ->
                IssueAnalysisFixtures.builder().category("x".repeat(256)).build())
                .isInstanceOf(AnalysisRejectedException.class)
                .hasMessageContaining("너무 깁니다");
    }

    @Test
    @DisplayName("implementationFeasible 은 primitive 다 — null 이 false 로 둔갑할 수 없다")
    void feasible_은_null_을_받지_못한다() {
        // 컴파일 타임 보장이라 런타임 단언이 아니다. 값이 두 상태뿐임을 고정해 둔다
        assertThat(IssueAnalysisFixtures.feasible().implementationFeasible()).isTrue();
        assertThat(IssueAnalysisFixtures.infeasible().implementationFeasible()).isFalse();
    }

    @Test
    @DisplayName("difficulty 는 DB 에 name() 으로 앉는다")
    void difficulty_는_name_으로_저장된다() {
        assertThat(IssueAnalysis.Difficulty.valueOf("HARD"))
                .isEqualTo(IssueAnalysis.Difficulty.HARD);
        assertThat(IssueAnalysis.Difficulty.values())
                .as("컬럼이 VARCHAR 라 CHECK 제약이 없다 — 어휘를 여기서 닫는다")
                .containsExactly(IssueAnalysis.Difficulty.EASY,
                        IssueAnalysis.Difficulty.MEDIUM,
                        IssueAnalysis.Difficulty.HARD);
    }

    @Test
    @DisplayName("confidence 반올림이 경계를 넘기지 않는다")
    void 반올림이_상한을_넘기지_않는다() {
        // 0.999 → 1.00. 범위 검사를 반올림 전에 하므로 통과해야 한다
        assertThat(IssueAnalysisFixtures.withConfidence("0.999").confidence())
                .isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("category 앞뒤 공백은 정리된다")
    void category_공백을_정리한다() {
        assertThat(IssueAnalysisFixtures.builder().category("  bug  ").build().category())
                .isEqualTo("bug");
    }

    @Test
    @DisplayName("BigDecimal 을 쓰는 이유 — double 이면 0.1+0.2 같은 값이 컬럼과 어긋난다")
    void confidence_는_BigDecimal_이다() {
        assertThat(IssueAnalysisFixtures.feasible().confidence()).isInstanceOf(BigDecimal.class);
    }
}
