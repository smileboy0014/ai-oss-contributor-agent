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

    // ───────────────────── 보류 해소 — Q-8 · #24 ─────────────────────

    @Test
    void 사람은_보류를_허용으로_풀_수_있다_S5() {
        RepositoryPolicy policy = RepositoryPolicy.pending(repo(), "CONTRIBUTING.adoc=5xx", CLOCK);

        policy.resolvePending(true, "adoc 을 직접 열어 확인했다 — AI 금지 문구 없음", CLOCK);

        assertThat(policy.allowsContribution()).isTrue();
        assertThat(policy.isHumanResolved())
                .as("resolvedAt 이 「기계 판정이 아니다」를 말한다")
                .isTrue();
        assertThat(policy.getPendingReason())
                .as("해소 뒤에도 보존한다 — 왜 보류였는지가 사라지면 판단을 재검토할 수 없다")
                .isEqualTo("CONTRIBUTING.adoc=5xx");
    }

    @Test
    void 사람은_보류를_금지로_닫을_수도_있다_S5() {
        RepositoryPolicy policy = RepositoryPolicy.pending(repo(), "AGENTS.md=UNKNOWN", CLOCK);

        policy.resolvePending(false, "AGENTS.md 에 AI 생성 기여 금지가 적혀 있다", CLOCK);

        assertThat(policy.isAiContributionForbidden())
                .as("🔴 「해소」는 「허용」이 아니다. 허용 전용으로 두면 금지 판정을 내리려고 "
                        + "DB 를 손으로 고치게 되고 그쪽이 더 위험하다 — Q-8")
                .isTrue();
        assertThat(policy.isHumanResolved()).isTrue();
    }

    @Test
    void 금지_판정은_해소로_뒤집히지_않는다_S5() {
        RepositoryPolicy policy = RepositoryPolicy.analyzed(repo(), forbidden(), CLOCK);

        assertThatThrownBy(() -> policy.resolvePending(true, "메인테이너가 괜찮다고 했다", CLOCK))
                .as("막지 않으면 FR-2 가 API 호출 한 번으로 풀린다")
                .isInstanceOf(PolicyResolutionRejectedException.class);

        assertThat(policy.isAiContributionForbidden()).isTrue();
        assertThat(policy.isHumanResolved())
                .as("거부는 흔적을 남기지 않는다")
                .isFalse();
    }

    @Test
    void 거부_메시지가_우회법을_안내하지_않는다_S5() {
        RepositoryPolicy policy = RepositoryPolicy.analyzed(repo(), forbidden(), CLOCK);

        // 🔴 게이트가 막으면서 「이렇게 하면 나를 지나갈 수 있다」를 적어 두면 그것이 관행이 된다.
        //    조치 방법을 적는 것 자체는 옳다 — 다만 그 방법이 「게이트를 통과하는 법」이어야지
        //    「게이트를 우회하는 법」이면 안 된다
        assertThatThrownBy(() -> policy.resolvePending(true, "근거", CLOCK))
                .hasMessageNotContainingAny("DB", "직접 수정", "UPDATE");
    }

    @Test
    void 이미_허용인_정책은_해소할_것이_없다() {
        RepositoryPolicy policy = RepositoryPolicy.analyzed(repo(), allowed(), CLOCK);

        assertThatThrownBy(() -> policy.resolvePending(true, "한 번 더 허용", CLOCK))
                .isInstanceOf(PolicyResolutionRejectedException.class);
    }

    @Test
    void 허용된_정책을_금지로_조이는_것은_언제든_가능하다_S5() {
        RepositoryPolicy policy = RepositoryPolicy.analyzed(repo(), allowed(), CLOCK);

        policy.resolvePending(false, "나중에 AGENTS.md 에 금지가 추가된 것을 사람이 확인했다", CLOCK);

        assertThat(policy.isAiContributionForbidden())
                .as("조이는 방향까지 막으면 규약이 바뀌어도 되돌릴 길이 없다 — "
                        + "「모르면 되돌릴 수 없는 쪽을 피한다」")
                .isTrue();
    }

    @Test
    void 근거_없는_해소는_남길_수_없다() {
        RepositoryPolicy policy = RepositoryPolicy.pending(repo(), "AGENTS.md=UNKNOWN", CLOCK);

        assertThatThrownBy(() -> policy.resolvePending(true, "   ", CLOCK))
                .as("왜 그렇게 판단했는지가 없으면 그 판정은 재검토도 못 한다")
                .isInstanceOf(PolicyResolutionRejectedException.class);

        assertThat(policy.isAiContributionUndetermined()).isTrue();
    }

    @Test
    void 해소_근거도_스크럽을_거친다_S4() {
        RepositoryPolicy policy = RepositoryPolicy.pending(repo(), "AGENTS.md=UNKNOWN", CLOCK);
        // 토큰 「형태」가 유의미한 테스트다 — 스크럽이 실제로 무는지를 봐야 하므로 조립한다
        String leaked = "ghp_" + "B".repeat(36);

        policy.resolvePending(true, "메인테이너 토큰 " + leaked + " 로 확인했다", CLOCK);

        assertThat(policy.getResolutionNote())
                .as("사람이 대상 저장소 원문이나 자기 토큰을 붙여넣는다 — 여기가 마지막 방어다")
                .doesNotContain(leaked);
    }

    @Test
    void 해소_근거는_컬럼_상한_안으로_잘린다() {
        RepositoryPolicy policy = RepositoryPolicy.pending(repo(), "AGENTS.md=UNKNOWN", CLOCK);

        policy.resolvePending(true, "가".repeat(5_000), CLOCK);

        assertThat(policy.getResolutionNote().length())
                .as("스크럽이 길이를 늘릴 수 있어 입력 상한(1000)과 컬럼(1024)을 두 축으로 둔다. "
                        + "도메인이 마지막으로 한 번 더 자른다")
                .isLessThanOrEqualTo(1024);
    }

    // ───────────── 해소 뒤 재분석 — 방향이 다르다 (Q-8 · #24) ─────────────

    @Test
    void 사람이_해소한_판정을_재분석이_허용으로_되돌리지_못한다_S5() {
        RepositoryPolicy policy = RepositoryPolicy.pending(repo(), "AGENTS.md=5xx", CLOCK);
        policy.resolvePending(true, "직접 읽었다 — 금지 문구 없음", CLOCK);

        assertThatThrownBy(() -> policy.reanalyze(allowed(), CLOCK))
                .as("Q-8 이 막으려는 것은 「자동이 사람 판단을 조용히 바꾸는 것」이다. "
                        + "해소가 그 방어를 리셋하면 안 된다")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 사람이_해소한_뒤에도_재분석이_금지로_조이는_것은_통과한다_S5() {
        RepositoryPolicy policy = RepositoryPolicy.pending(repo(), "AGENTS.md=5xx", CLOCK);
        policy.resolvePending(true, "직접 읽었다 — 금지 문구 없음", CLOCK);

        policy.reanalyze(forbidden(), CLOCK);

        assertThat(policy.isAiContributionForbidden())
                .as("🔴 양방향으로 막으면 대상 저장소가 나중에 AI 금지를 추가해도 영영 못 본다. "
                        + "그 방향은 되돌릴 수 없는 쪽(규약 위반 PR)이라 열어 둔다")
                .isTrue();
    }
}
