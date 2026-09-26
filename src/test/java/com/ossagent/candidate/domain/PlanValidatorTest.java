package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.candidate.domain.PlannedFile.ChangeKind;
import com.ossagent.repository.domain.ContributionConstraints;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 계획을 실행하기 전에 거른다 — 이슈 #16 FR-2~FR-4.
 *
 * <p>여기서 거르면 LLM 호출 한 번이고, 못 거르면 샌드박스 30분이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanValidatorTest {

    private static final String SOURCE = "src/main/java/org/x/Poller.java";
    private static final String TEST = "src/test/java/org/x/PollerTests.java";

    private static final ContributionConstraints TESTS_REQUIRED =
            new ContributionConstraints("21", "./gradlew build", "./gradlew test", true, false, false);
    private static final ContributionConstraints TESTS_OPTIONAL =
            new ContributionConstraints("21", "./gradlew build", "./gradlew test", false, false, false);

    /** 고정 상한만 걸고 이슈별 검사는 끈 눈금 — 검사 하나씩 떼어 보기 위한 것 */
    private static ScopeLimits looseLimits() {
        return new ScopeLimits(8, 400, null, null, 2.0);
    }

    private static ImplementationPlan planOf(PlannedFile... files) {
        return new ImplementationPlan(List.of(files), "요약", "테스트 전략", 100);
    }

    private static PlannedFile modify(String path) {
        return new PlannedFile(path, ChangeKind.MODIFY, "고친다");
    }

    private static PlannedFile create(String path) {
        return new PlannedFile(path, ChangeKind.CREATE, "만든다");
    }

    // ── FR-2 실재 ────────────────────────────────────────────────────────────

    @Test
    void 컨텍스트에_없는_파일을_MODIFY_로_지목하면_거부한다() {
        var verdict = PlanValidator.validate(planOf(modify("src/main/java/org/x/Ghost.java")),
                Set.of(SOURCE), false, TESTS_OPTIONAL, looseLimits());

        assertThat(verdict.isPassed())
                .as("모델에게 준 것이 그 목록이다 — 보여주지 않은 파일을 고치겠다는 계획은 근거가 없다")
                .isFalse();
        assertThat(verdict.violations().get(0)).contains("Ghost.java");
    }

    @Test
    void 신규_생성은_실재_검사에서_빠진다() {
        var verdict = PlanValidator.validate(
                planOf(modify(SOURCE), create("src/test/java/org/x/NewTests.java")),
                Set.of(SOURCE), false, TESTS_OPTIONAL, looseLimits());

        assertThat(verdict.isPassed())
                .as("새로 만들 파일이 아직 없는 것은 당연하다 — 여기서 막으면 테스트 추가가 영영 불가능하다")
                .isTrue();
    }

    @Test
    void 이미_있는_파일을_CREATE_로_지목하면_거부한다() {
        var verdict = PlanValidator.validate(planOf(create(SOURCE)),
                Set.of(SOURCE), false, TESTS_OPTIONAL, looseLimits());

        assertThat(verdict.isPassed())
                .as("#18 이 그대로 실행하면 남의 파일을 덮어쓴다")
                .isFalse();
    }

    @Test
    void 컨텍스트가_잘렸으면_사유에_그_사실을_적는다() {
        var verdict = PlanValidator.validate(planOf(modify("src/main/java/org/x/Ghost.java")),
                Set.of(SOURCE), true, TESTS_OPTIONAL, looseLimits());

        assertThat(verdict.violations().get(0))
                .as("무고한 거부일 수 있다 — 재생성이 「목록 안에서 고르라」로 읽혀야 한다")
                .contains("잘렸다");
    }

    // ── FR-3 규약 (S-5) ──────────────────────────────────────────────────────

    @Test
    void 테스트_필수인데_전략이_비면_거부한다_S5() {
        var plan = new ImplementationPlan(List.of(modify(SOURCE), modify(TEST)), "요약", "", 100);

        var verdict = PlanValidator.validate(plan, Set.of(SOURCE, TEST), false,
                TESTS_REQUIRED, looseLimits());

        assertThat(verdict.isPassed())
                .as("규약 위반 PR 은 읽히지 않고 닫힌다 — 30분을 태운 뒤에 알면 늦다")
                .isFalse();
    }

    @Test
    void 테스트_필수인데_테스트_파일이_없으면_거부한다_S5() {
        var verdict = PlanValidator.validate(planOf(modify(SOURCE)),
                Set.of(SOURCE), false, TESTS_REQUIRED, looseLimits());

        assertThat(verdict.isPassed()).isFalse();
        assertThat(verdict.violations())
                .anyMatch(it -> it.contains("테스트 파일이 하나도 없다"));
    }

    @Test
    void 테스트가_필수가_아니면_전략이_없어도_통과한다() {
        var plan = new ImplementationPlan(List.of(modify(SOURCE)), "요약", "", 100);

        assertThat(PlanValidator.validate(plan, Set.of(SOURCE), false, TESTS_OPTIONAL,
                looseLimits()).isPassed()).isTrue();
    }

    // ── FR-4 범위 (D-6 두 눈금) ──────────────────────────────────────────────

    @Test
    void 고정_상한을_넘으면_거부한다() {
        PlannedFile[] files = new PlannedFile[9];
        for (int i = 0; i < files.length; i++) {
            files[i] = modify("src/main/java/org/x/F" + i + ".java");
        }
        Set<String> shown = Set.of(files[0].path(), files[1].path(), files[2].path(),
                files[3].path(), files[4].path(), files[5].path(), files[6].path(),
                files[7].path(), files[8].path());

        var verdict = PlanValidator.validate(planOf(files), shown, false, TESTS_OPTIONAL,
                new ScopeLimits(8, 400, null, null, 2.0));

        assertThat(verdict.violations()).anyMatch(it -> it.contains("상한 8개"));
    }

    @Test
    void 이슈_추정치를_크게_넘으면_거부한다() {
        // 고정 상한(8)은 통과하지만 이슈 추정(1파일 × 2.0 = 2)을 넘는다 —
        // 고정 상한만 두면 이런 무관한 계획이 그대로 통과한다
        var plan = planOf(modify(SOURCE), modify(TEST), modify("src/main/java/org/x/A.java"),
                modify("src/main/java/org/x/B.java"));
        Set<String> shown = Set.of(SOURCE, TEST, "src/main/java/org/x/A.java",
                "src/main/java/org/x/B.java");

        var verdict = PlanValidator.validate(plan, shown, false, TESTS_OPTIONAL,
                new ScopeLimits(8, 400, 1, 50, 2.0));

        assertThat(verdict.violations())
                .as("「범위가 이슈를 넘지 않는가」는 이슈마다 눈금이 다르다")
                .anyMatch(it -> it.contains("분석 추정"));
    }

    @Test
    void 추정치가_없으면_이슈별_검사를_건너뛴다() {
        var plan = planOf(modify(SOURCE), modify(TEST));

        var verdict = PlanValidator.validate(plan, Set.of(SOURCE, TEST), false, TESTS_OPTIONAL,
                new ScopeLimits(8, 400, 0, 0, 2.0));

        assertThat(verdict.isPassed())
                .as("모르는 것을 최강 제약으로 번역하면 모든 계획이 거부된다")
                .isTrue();
    }

    @Test
    void 여유는_추정치보다_넉넉하다() {
        // 추정 2파일 × 2.0 = 4 → 3파일 계획은 통과해야 한다.
        // 1.0 으로 조이면 정상 기여가 상한 소진으로 죽는다
        var plan = planOf(modify(SOURCE), modify(TEST), modify("src/main/java/org/x/A.java"));
        Set<String> shown = Set.of(SOURCE, TEST, "src/main/java/org/x/A.java");

        assertThat(PlanValidator.validate(plan, shown, false, TESTS_OPTIONAL,
                new ScopeLimits(8, 400, 2, 1000, 2.0)).isPassed()).isTrue();
    }

    // ── 사유 ─────────────────────────────────────────────────────────────────

    @Test
    void 거부_사유는_재생성_프롬프트로_나간다() {
        var verdict = PlanValidator.validate(planOf(modify("src/main/java/org/x/Ghost.java")),
                Set.of(SOURCE), false, TESTS_OPTIONAL, looseLimits());

        assertThat(verdict.asFeedback())
                .as("사유가 비면 모델이 무엇을 고쳐야 할지 모른 채 같은 계획을 다시 낸다")
                .contains("Ghost.java")
                .contains("다시 낸다");
    }
}
