package com.ossagent.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>계획 밖 파일 판정의 최종 게이트</b> — #18 · 이슈 완료 조건 1.
 *
 * <h2>왜 이것이 최종인가</h2>
 *
 * <p>{@code CodingAgent} 의 조기 차단은 <b>모델 출력</b>만 본다. 그런데 이슈가 요구한 것은
 * 「계획에 없는 파일을 <b>건드리면</b> 중단」이다 — <b>샌드박스에서 도는 포맷터</b>가
 * 워크스페이스를 RW 로 잡고 임의 파일을 고치고, <b>그것은 모델 출력에 나타나지 않는다.</b>
 * {@code spotlessApply} 한 번이면 전 저장소가 바뀐다.
 *
 * <p>그래서 판정을 <b>diff 의 경로 집합</b>에 건다 — 무엇이 건드렸든 여기서 드러난다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WorkspaceDiffTest {

    @Test
    @DisplayName("🔴 계획 밖 경로를 짚어낸다 — 포맷터가 건드린 것도 여기서 드러난다")
    void 계획_밖_경로를_짚어낸다() {
        WorkspaceDiff diff = new WorkspaceDiff("--- a/x\n+++ b/x\n",
                Set.of("src/A.java", "build.gradle", "README.md"));

        assertThat(diff.outsideOf(Set.of("src/A.java")))
                .as("계획은 A.java 하나였는데 포맷터가 둘을 더 건드렸다")
                .containsExactlyInAnyOrder("build.gradle", "README.md");
    }

    @Test
    @DisplayName("계획 안이면 비어 있다")
    void 계획_안이면_비어_있다() {
        WorkspaceDiff diff = new WorkspaceDiff("d", Set.of("src/A.java"));

        assertThat(diff.outsideOf(Set.of("src/A.java", "src/B.java"))).isEmpty();
    }

    @Test
    @DisplayName("🔴 경로 집합이 null 이면 거부한다 — 「모른다」를 「없다」로 뭉개지 않는다")
    void 경로_집합이_null_이면_거부한다() {
        // 🔴 null 을 빈 집합으로 뭉개면 「계획 밖 파일이 없다」로 읽혀 게이트가 조용히 통과한다.
        //    이 저장소에서 반복해 아픈 자리다 — #28·#64·#66 이 전부 같은 모양이었다
        assertThatThrownBy(() -> new WorkspaceDiff("d", null))
                .isInstanceOf(IllegalArgumentException.class);

        WorkspaceDiff diff = new WorkspaceDiff("d", Set.of("src/A.java"));
        assertThatThrownBy(() -> diff.outsideOf(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("빈 diff 는 유효하다 — 「바뀐 것이 없다」도 사실이다")
    void 빈_diff_는_유효하다() {
        WorkspaceDiff diff = new WorkspaceDiff("", Set.of());

        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.outsideOf(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("diff 본문이 null 이면 거부한다 — 「못 만들었다」와 「변경 없음」은 다르다")
    void diff_본문이_null_이면_거부한다() {
        assertThatThrownBy(() -> new WorkspaceDiff(null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
