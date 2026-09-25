package com.ossagent.repository.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 규약 엔티티의 불변식 — S-5.
 *
 * <p>여기서 지키는 것은 「보류·금지는 재분석으로 풀리지 않는다」(Q-8 확정 ②)다.
 * UseCase 의 {@code if} 가 아니라 <b>엔티티</b>가 거부해야 한다 — 조건문은 다음 사람이 지운다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RepositoryPolicyTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC);

    private static OssRepository repo() {
        return new OssRepository("spring-projects", "spring-kafka",
                "https://github.com/spring-projects/spring-kafka");
    }

    private static RuleReading allowed() {
        return new RuleReading(true, "17", "./gradlew build", "./gradlew test",
                true, true, true, ScrubbedRules.of("{}"));
    }

    private static RuleReading forbidden() {
        return new RuleReading(false, null, null, null, false, false, false, ScrubbedRules.none());
    }

    @Test
    void 허용_판정은_그대로_고정된다() {
        RepositoryPolicy policy = RepositoryPolicy.analyzed(repo(), allowed(), CLOCK);

        assertThat(policy.allowsContribution()).isTrue();
        assertThat(policy.isAiContributionUndetermined()).isFalse();
        assertThat(policy.getJavaVersion()).isEqualTo("17");
        assertThat(policy.getAnalyzedAt()).isEqualTo(Instant.parse("2026-09-25T00:00:00Z"));
    }

    @Test
    void 보류는_allowed_가_NULL_이고_사유를_갖는다_S5() {
        RepositoryPolicy policy = RepositoryPolicy.pending(repo(), "AGENTS.md=UNKNOWN", CLOCK);

        assertThat(policy.isAiContributionUndetermined())
                .as("NULL = 판정 실패 = 보류. 「허용」이 아니다")
                .isTrue();
        assertThat(policy.allowsContribution()).isFalse();
        assertThat(policy.getPendingReason()).isEqualTo("AGENTS.md=UNKNOWN");
    }

    @Test
    void 판정이_서지_않은_결과로_analyzed_를_만들_수_없다_S5() {
        assertThatThrownBy(() -> RepositoryPolicy.analyzed(repo(), RuleReading.undetermined(), CLOCK))
                .as("한 팩토리가 둘 다 처리하면 「보류인데 사유가 없는」 행이 생긴다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 사유_없는_보류를_만들_수_없다_S5() {
        assertThatThrownBy(() -> RepositoryPolicy.pending(repo(), "  ", CLOCK))
                .as("보류는 사람이 푼다 — 근거가 없으면 판단할 수가 없다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 보류_상태에_재분석을_돌려도_허용으로_바뀌지_않는다_S5() {
        RepositoryPolicy policy = RepositoryPolicy.pending(repo(), "CONTRIBUTING.md=UNKNOWN", CLOCK);

        // 허용 판정을 던져도 거부해야 한다 — 입력의 우연이 아니라 구조로 막는다
        assertThatThrownBy(() -> policy.reanalyze(allowed(), CLOCK))
                .as("Q-8 확정 ② — 보류는 재분석·시간경과·횟수소진으로 풀리지 않는다")
                .isInstanceOf(IllegalStateException.class);

        assertThat(policy.isAiContributionUndetermined()).isTrue();
    }

    @Test
    void 금지_판정은_재분석으로_뒤집히지_않는다_S5() {
        RepositoryPolicy policy = RepositoryPolicy.analyzed(repo(), forbidden(), CLOCK);

        assertThatThrownBy(() -> policy.reanalyze(allowed(), CLOCK))
                .as("막지 않으면 FR-2(AI 기여 금지 저장소 제외)가 재분석 한 번으로 풀린다")
                .isInstanceOf(IllegalStateException.class);

        assertThat(policy.isAiContributionForbidden()).isTrue();
    }

    @Test
    void 허용_상태에서는_재분석으로_규약을_갱신할_수_있다() {
        RepositoryPolicy policy = RepositoryPolicy.analyzed(repo(), allowed(), CLOCK);

        policy.reanalyze(new RuleReading(true, "21", "mvn verify", "mvn test",
                false, false, false, ScrubbedRules.of("{}")), CLOCK);

        assertThat(policy.getJavaVersion())
                .as("규약은 바뀐다 — 허용 상태의 갱신까지 막으면 재분석 자체가 무의미해진다")
                .isEqualTo("21");
    }

    @Test
    void 재분석은_보류_사유를_지운다() {
        RepositoryPolicy policy = RepositoryPolicy.analyzed(repo(), allowed(), CLOCK);
        assertThat(policy.getPendingReason()).isNull();
    }

    @Test
    void 규약_요약은_스크럽을_거쳐야만_들어온다_S4() {
        String leaked = "ghp_" + "A".repeat(36);
        RuleReading reading = new RuleReading(true, null, null, null, false, false, false,
                ScrubbedRules.of("{\"evidence\":\"" + leaked + "\"}"));

        RepositoryPolicy policy = RepositoryPolicy.analyzed(repo(), reading, CLOCK);

        assertThat(policy.getContributionRules())
                .as("이 컬럼은 PR 본문 조립(#23)의 입력이 될 수 있다 — 종착지가 공개 PR 이다")
                .doesNotContain(leaked);
    }
}
