package com.ossagent.repository.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.repository.domain.ContextKeyword.Kind;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 경로 점수화 — 이슈 #15 FR-2·FR-3. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RelevanceScorerTest {

    private static RepositoryTree treeOf(String... paths) {
        return new RepositoryTree("sha", List.of(paths).stream()
                .map(p -> new RepositoryTreeEntry(p, RepositoryTreeEntry.EntryType.BLOB, 0))
                .toList(), false);
    }

    @Test
    void 경로_리터럴이_타입_이름보다_앞선다() {
        var tree = treeOf("src/main/java/org/x/Poller.java", "src/main/java/org/y/KafkaTemplate.java");
        var keywords = List.of(
                new ContextKeyword("src/main/java/org/x/Poller.java", Kind.PATH_LITERAL),
                new ContextKeyword("KafkaTemplate", Kind.TYPE_NAME));

        var ranked = RelevanceScorer.rank(tree, keywords);

        assertThat(ranked).extracting(RelevanceScorer.ScoredPath::path)
                .as("사람이 직접 짚은 경로가 가장 믿을 만하다")
                .containsExactly("src/main/java/org/x/Poller.java",
                        "src/main/java/org/y/KafkaTemplate.java");
    }

    @Test
    void 점수가_같으면_경로_사전순으로_깬다() {
        var tree = treeOf("src/b/KafkaTemplate.java", "src/a/KafkaTemplate.java");
        var keywords = List.of(new ContextKeyword("KafkaTemplate", Kind.TYPE_NAME));

        var ranked = RelevanceScorer.rank(tree, keywords);

        assertThat(ranked).extracting(RelevanceScorer.ScoredPath::path)
                .as("같은 입력에 같은 선별이 나와야 재현이 가능하다 (NFR-4)")
                .containsExactly("src/a/KafkaTemplate.java", "src/b/KafkaTemplate.java");
    }

    @Test
    void 짧은_파일명이_긴_키워드에_걸리지_않는다() {
        var tree = treeOf("src/main/java/org/x/Foo.java");
        var keywords = List.of(new ContextKeyword("FooBarBazContainer", Kind.TYPE_NAME));

        assertThat(RelevanceScorer.rank(tree, keywords))
                .as("반대 방향까지 세면 무관한 짧은 이름이 전부 올라온다")
                .isEmpty();
    }

    @Test
    void 패키지는_디렉터리로_바꿔_맞춘다() {
        var tree = treeOf("src/main/java/org/springframework/kafka/listener/A.java");
        var keywords = List.of(
                new ContextKeyword("org.springframework.kafka.listener", Kind.PACKAGE));

        assertThat(RelevanceScorer.rank(tree, keywords))
                .extracting(RelevanceScorer.ScoredPath::reason)
                .containsExactly(SelectionReason.PACKAGE);
    }

    @Test
    void 소스가_아닌_경로는_점수를_매기지_않는다() {
        var tree = treeOf("build/classes/KafkaTemplate.class", "docs/KafkaTemplate.png");
        var keywords = List.of(new ContextKeyword("KafkaTemplate", Kind.TYPE_NAME));

        assertThat(RelevanceScorer.rank(tree, keywords)).isEmpty();
    }

    @Test
    void 같은_디렉터리의_테스트_짝을_찾는다() {
        var tree = treeOf("src/main/java/org/x/Poller.java", "src/test/java/org/x/PollerTests.java");

        assertThat(RelevanceScorer.testPairsOf(tree.blobPaths(), "src/main/java/org/x/Poller.java"))
                .as("표준 메이븐/그래들 배치에서 main ↔ test 를 건넌다")
                .containsExactly("src/test/java/org/x/PollerTests.java");
    }

    @Test
    void 트리에_없는_테스트_짝은_돌려주지_않는다() {
        var tree = treeOf("src/main/java/org/x/Poller.java");

        assertThat(RelevanceScorer.testPairsOf(tree.blobPaths(), "src/main/java/org/x/Poller.java"))
                .as("실재하지 않는 경로를 후보로 올리면 호출을 낭비한다")
                .isEmpty();
    }

    @Test
    void 키워드가_없으면_후보가_없다() {
        assertThat(RelevanceScorer.rank(treeOf("src/main/java/A.java"), List.of())).isEmpty();
    }
}
