package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.candidate.domain.PlannedFile.ChangeKind;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 계획 값의 스키마·스크럽 강제 — 이슈 #16. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ImplementationPlanTest {

    private static PlannedFile file(String path) {
        return new PlannedFile(path, ChangeKind.MODIFY, "고친다");
    }

    @Test
    void 고칠_파일이_없으면_계획이_아니다() {
        assertThatThrownBy(() -> new ImplementationPlan(List.of(), "요약", "", 10))
                .isInstanceOf(PlanRejectedException.class);
    }

    @Test
    void 같은_파일을_두_번_지목하면_거부한다() {
        assertThatThrownBy(() -> new ImplementationPlan(
                List.of(file("a/B.java"), file("a/B.java")), "요약", "", 10))
                .as("#18 이 무엇을 기준으로 할지 알 수 없다")
                .isInstanceOf(PlanRejectedException.class);
    }

    @Test
    void 모르는_변경_종류를_조용히_눕히지_않는다() {
        assertThatThrownBy(() -> ChangeKind.from("DELETE"))
                .as("어휘에 없으면 모델이 계획할 수 없다 — 표현 불가능하게 만드는 것이 플래그보다 강하다")
                .isInstanceOf(PlanRejectedException.class);
    }

    @Test
    void 요약은_스크럽을_거친다_S4() {
        // 토큰 「모양」이 유의미한 테스트라 조립한다 (testing-philosophy 픽스처 규약의 예외)
        String token = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8";

        var plan = new ImplementationPlan(List.of(file("a/B.java")),
                "요약 " + token, "전략 " + token, 10);

        assertThat(plan.summary()).doesNotContain(token).contains("***REDACTED***");
        assertThat(plan.testStrategy()).doesNotContain(token);
    }

    @Test
    void 파일_의도도_스크럽을_거친다_S4() {
        String token = "ghp_" + "Z9y8X7w6V5u4T3s2R1q0P9o8N7m6L5k4J3i2";

        var planned = new PlannedFile("a/B.java", ChangeKind.MODIFY, "이유 " + token);

        assertThat(planned.intent()).doesNotContain(token);
    }

    @Test
    void 본문을_찍지_않는다_S4() {
        var plan = new ImplementationPlan(List.of(file("a/B.java")), "매우 비밀스러운 요약", "", 10);

        assertThat(plan.toString())
                .doesNotContain("매우 비밀스러운 요약")
                .as("경로는 남긴다 — 영속화하지 않으므로 로그가 유일한 기록이다")
                .contains("a/B.java");
    }

    @Test
    void 상위_참조가_섞인_경로를_거부한다() {
        // 🔴 CREATE 경로는 실재 대조를 통과할 수 없어 사실상 모델이 지어낸 문자열이다.
        //    그 값을 받아 실제로 파일을 만드는 것이 #18 이다
        assertThatThrownBy(() -> new PlannedFile("src/../../etc/passwd", ChangeKind.CREATE, "만든다"))
                .isInstanceOf(PlanRejectedException.class);
        assertThatThrownBy(() -> new PlannedFile("/etc/passwd", ChangeKind.CREATE, "만든다"))
                .isInstanceOf(PlanRejectedException.class);
        assertThatThrownBy(() -> new PlannedFile("a/B.java\nGET /admin", ChangeKind.MODIFY, "고친다"))
                .isInstanceOf(PlanRejectedException.class);
    }

    @Test
    void 이름_안의_점_두_개는_막지_않는다() {
        assertThat(new PlannedFile("src/foo..bar/Baz.java", ChangeKind.MODIFY, "고친다").path())
                .as("문자열 검사가 아니라 세그먼트 검사다 — 무고한 이름을 막지 않는다")
                .isEqualTo("src/foo..bar/Baz.java");
    }

    @Test
    void 경로도_스크럽을_거친다_S4() {
        String token = "ghp_" + "Q1w2E3r4T5y6U7i8O9p0A1s2D3f4G5h6J7k8";

        var planned = new PlannedFile("src/" + token + "/B.java", ChangeKind.CREATE, "만든다");

        assertThat(planned.path())
                .as("intent 만 스크럽하고 path 를 면제하면 비대칭이다 — 같은 응답에서 왔다")
                .doesNotContain(token);
    }

    @Test
    void 신규와_수정을_가른다() {
        var plan = new ImplementationPlan(
                List.of(file("a/B.java"), new PlannedFile("a/C.java", ChangeKind.CREATE, "만든다")),
                "요약", "", 10);

        assertThat(plan.modifiedFiles()).extracting(PlannedFile::path).containsExactly("a/B.java");
        assertThat(plan.createdFiles()).extracting(PlannedFile::path).containsExactly("a/C.java");
    }
}
