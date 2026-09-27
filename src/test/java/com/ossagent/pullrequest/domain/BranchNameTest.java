package com.ossagent.pullrequest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 대상 저장소 브랜치 이름 — PRD §14. 우리 저장소 컨벤션과 섞지 않는다. */
class BranchNameTest {

    @Test
    @DisplayName("PRD §14 형식으로 만든다")
    void PRD_형식을_따른다() {
        assertThat(BranchName.of(1234, "Fix flaky consumer test").value())
                .isEqualTo("oss-agent/issue-1234-fix-flaky-consumer-test");
    }

    @Test
    @DisplayName("우리 저장소 브랜치 컨벤션을 쓰지 않는다")
    void 우리_컨벤션과_다르다() {
        String value = BranchName.of(12, "scanner").value();

        assertThat(value)
                .as("feature/12_slug 는 이 저장소 안에서만 유효하다 — git-workflow.md")
                .startsWith("oss-agent/issue-")
                .doesNotContain("feature/")
                .doesNotContain("_");
    }

    @Test
    @DisplayName("ref 경로를 조작하는 문자는 전부 구분자로 접힌다")
    void ref_주입을_막는다() {
        // 🔴 거부목록이 아니라 여집합이다 — 허용 문자만 남기므로 새 위험 형태가 생겨도 걸러진다
        for (String hostile : new String[] {
                "../../heads/main", "x@{0}", "x~1", "x^", "foo.lock", "a:b?c*d[e]", "a\nb"}) {
            String value = BranchName.of(7, hostile).value();

            assertThat(value)
                    .as("입력=%s", hostile)
                    .matches("oss-agent/issue-7(-[a-z0-9-]*[a-z0-9])?")
                    .doesNotContain("..")
                    .doesNotContain("@{")
                    .doesNotContain("~")
                    .doesNotContain("^")
                    .doesNotContain(":");
            assertThat(value.endsWith(".lock")).isFalse();
        }
    }

    @Test
    @DisplayName("설명이 전부 비 ASCII 면 이슈 번호만으로 만든다 — 예외를 던지지 않는다")
    void 한국어_제목도_브랜치를_만든다() {
        BranchName name = BranchName.of(99, "이슈 제목이 전부 한국어다");

        assertThat(name.value())
                .as("여기서 던지면 정상 이슈가 브랜치를 못 만든다. 식별은 이슈 번호가 한다")
                .isEqualTo("oss-agent/issue-99");
    }

    @Test
    @DisplayName("slug 를 잘라도 구분자로 끝나지 않는다")
    void 상한에서_잘라도_모양이_유지된다() {
        BranchName name = BranchName.of(1, "a".repeat(10) + " " + "b".repeat(80));

        assertThat(name.value()).doesNotEndWith("-");
        assertThat(name.value().length()).isLessThanOrEqualTo(BranchName.MAX_LENGTH);
    }

    @Test
    @DisplayName("이슈 번호는 1 이상이다")
    void 이슈_번호가_없으면_거부한다() {
        assertThatThrownBy(() -> BranchName.of(0, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refName 은 Git Data API 가 요구하는 refs/heads 표기다")
    void refName은_refs_heads_다() {
        assertThat(BranchName.of(5, "x").refName()).isEqualTo("refs/heads/oss-agent/issue-5-x");
    }

    @Test
    @DisplayName("형식에 맞지 않는 값으로는 직접 만들 수 없다")
    void 임의_문자열로_만들_수_없다() {
        for (String bad : new String[] {"main", "refs/heads/oss-agent/issue-1", "oss-agent/issue-",
                "oss-agent/issue-1-", "oss-agent/issue-1-UPPER"}) {
            assertThatThrownBy(() -> new BranchName(bad))
                    .as("입력=%s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
