package com.ossagent.repository.adapter.out.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.repository.domain.DocumentFetchOutcome;
import com.ossagent.repository.domain.FetchedDocument;
import com.ossagent.repository.domain.PolicyDocumentPath;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryDocuments;
import com.ossagent.repository.domain.RepositoryFile;
import com.ossagent.repository.domain.RepositoryMetadata;
import com.ossagent.repository.domain.RepositorySource;
import com.ossagent.repository.domain.UnreadableReason;
import com.ossagent.support.github.GitHubRateLimitException;
import com.ossagent.support.github.GitHubTransientException;
import com.ossagent.support.github.GitHubUnreadableContentException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 수집 실패를 우리 어휘로 번역하는 층 — S-5 의 핵심.
 *
 * <p>여기가 틀리면 「규약이 있는데 못 읽은 것」이 「규약 없음 → 허용」으로 번역되고,
 * 규약 위반 PR 이 외부 OSS 로 나간다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitHubPolicyDocumentSourceTest {

    private static final RepositoryCoordinates REPO =
            new RepositoryCoordinates("spring-projects", "spring-kafka");

    /** 경로별로 다른 결과를 주는 스텁. 공용 {@code FakeRepositorySource} 는 실패가 전역이다. */
    private static final class PerPathSource implements RepositorySource {
        private final Map<String, Supplier<Optional<RepositoryFile>>> byPath = new HashMap<>();

        PerPathSource read(String path, String content) {
            byPath.put(path, () -> Optional.of(new RepositoryFile(path, content)));
            return this;
        }

        PerPathSource absent(String path) {
            byPath.put(path, Optional::empty);
            return this;
        }

        PerPathSource fails(String path, RuntimeException e) {
            byPath.put(path, () -> {
                throw e;
            });
            return this;
        }

        @Override
        public RepositoryMetadata fetchMetadata(RepositoryCoordinates coordinates) {
            return new RepositoryMetadata(coordinates, "main", "Java", false, false, 0);
        }

        @Override
        public Optional<RepositoryFile> fetchFile(RepositoryCoordinates coordinates, String path,
                String ref) {
            return byPath.getOrDefault(path, Optional::empty).get();
        }
    }

    private static List<PolicyDocumentPath> paths(String... names) {
        return java.util.Arrays.stream(names)
                .map(n -> new PolicyDocumentPath(n, PolicyDocumentPath.Role.REQUIRED))
                .toList();
    }

    private static FetchedDocument only(RepositoryDocuments docs) {
        return docs.documents().get(0);
    }

    @Test
    void 파일이_없으면_ABSENT_다_404_하나뿐이다_S5() {
        var source = new GitHubPolicyDocumentSource(new PerPathSource().absent("AGENTS.md"), 40_000);

        FetchedDocument doc = only(source.collect(REPO, paths("AGENTS.md")));

        assertThat(doc.outcome())
                .as("Optional.empty() 는 404 하나뿐이다 — #6 계약. 다른 실패를 여기 넣으면 「규약 없음」 오역")
                .isEqualTo(DocumentFetchOutcome.ABSENT);
    }

    @Test
    void 레이트리밋은_일시적_실패로_분류된다_S5() {
        var limit = new GitHubRateLimitException(403, GitHubRateLimitException.Scope.PRIMARY,
                Instant.parse("2026-09-25T01:00:00Z"), Duration.ofMinutes(30), "리밋");
        var source = new GitHubPolicyDocumentSource(
                new PerPathSource().fails("CONTRIBUTING.md", limit), 40_000);

        FetchedDocument doc = only(source.collect(REPO, paths("CONTRIBUTING.md")));

        assertThat(doc.reason()).isEqualTo(UnreadableReason.RATE_LIMITED);
        assertThat(doc.isTransientFailure())
                .as("리밋으로 보류를 만들면 한 시간 뒤면 풀렸을 일이 영구 보류가 된다 (#24 미구현)")
                .isTrue();
    }

    @Test
    void 서버_오류는_일시적_실패로_분류된다_S5() {
        var source = new GitHubPolicyDocumentSource(
                new PerPathSource().fails("CONTRIBUTING.md", new GitHubTransientException(503, "5xx")),
                40_000);

        FetchedDocument doc = only(source.collect(REPO, paths("CONTRIBUTING.md")));

        assertThat(doc.reason()).isEqualTo(UnreadableReason.SERVER_ERROR);
        assertThat(doc.isTransientFailure()).isTrue();
    }

    @Test
    void 분류하지_못하는_예외도_읽지_못한_것으로_받는다_S5() {
        // 읽기 타임아웃이 CancellationException 으로 타입 없이 올라오는 구멍이 실제로 있다
        var source = new GitHubPolicyDocumentSource(
                new PerPathSource().fails("CONTRIBUTING.md", new CancellationException("취소됨")),
                40_000);

        FetchedDocument doc = only(source.collect(REPO, paths("CONTRIBUTING.md")));

        assertThat(doc.outcome())
                .as("타입을 쫓아가는 방식은 새 예외마다 구멍이 난다 — 가르는 선은 「읽었는가」다")
                .isEqualTo(DocumentFetchOutcome.UNREADABLE);
        assertThat(doc.reason()).isEqualTo(UnreadableReason.UNKNOWN);
        assertThat(doc.isTransientFailure())
                .as("모르는 실패를 일시적이라고 낙관하지 않는다 — 영구로 보고 사람에게 넘긴다")
                .isFalse();
    }

    @Test
    void 내용을_못_받은_경우는_영구_실패다_S5() {
        var source = new GitHubPolicyDocumentSource(
                new PerPathSource().fails("CONTRIBUTING.md",
                        new GitHubUnreadableContentException("1MB 초과")),
                40_000);

        FetchedDocument doc = only(source.collect(REPO, paths("CONTRIBUTING.md")));

        assertThat(doc.reason()).isEqualTo(UnreadableReason.UNKNOWN);
        assertThat(doc.isTransientFailure()).isFalse();
    }

    @Test
    void 상한을_넘는_문서는_절단하지_않고_보류한다_S5() {
        var source = new GitHubPolicyDocumentSource(
                new PerPathSource().read("CONTRIBUTING.md", "가".repeat(101)), 100);

        FetchedDocument doc = only(source.collect(REPO, paths("CONTRIBUTING.md")));

        assertThat(doc.outcome())
                .as("잘린 뒷부분에 금지 문구가 있었는지 판정할 방법이 없다")
                .isEqualTo(DocumentFetchOutcome.UNREADABLE);
        assertThat(doc.reason()).isEqualTo(UnreadableReason.TRUNCATED);
        assertThat(doc.isTransientFailure())
                .as("다시 읽어도 같은 크기다 — 사람이 봐야 한다")
                .isFalse();
    }

    @Test
    void 한_경로가_실패해도_나머지를_계속_수집한다_S5() {
        var source = new GitHubPolicyDocumentSource(new PerPathSource()
                .fails("AGENTS.md", new GitHubTransientException(503, "5xx"))
                .read("CONTRIBUTING.md", "기여 방법"), 40_000);

        RepositoryDocuments docs = source.collect(REPO, paths("AGENTS.md", "CONTRIBUTING.md"));

        assertThat(docs.documents()).hasSize(2);
        assertThat(docs.readDocuments())
                .as("한 경로가 5xx 라고 나머지 수집을 포기하면 판정 근거를 잃는다")
                .hasSize(1);
    }

    @Test
    void 확장자_변종이_후보_경로에_들어_있다_S5() {
        List<String> required = PolicyDocumentPath.REQUIRED_PATHS.stream()
                .map(PolicyDocumentPath::path)
                .toList();

        assertThat(required)
                .as("spring-boot·spring-data-redis 는 CONTRIBUTING.adoc 이다 — "
                        + ".md 만 찾으면 404 를 「규약 없음 → 허용」으로 오역한다")
                .contains("CONTRIBUTING.md", "CONTRIBUTING.adoc", "CONTRIBUTING.rst", "CONTRIBUTING",
                        ".github/CONTRIBUTING.md", ".github/CONTRIBUTING.adoc",
                        "AGENTS.md", "CLAUDE.md");
    }

    @Test
    void PR_템플릿과_README_는_부가_수집이다() {
        List<String> supplementary = PolicyDocumentPath.SUPPLEMENTARY_PATHS.stream()
                .map(PolicyDocumentPath::path)
                .toList();

        assertThat(supplementary)
                .as("S-5 표가 PR 템플릿을 직접 열거한다 — 빠뜨리면 요구 누락이다")
                .contains(".github/PULL_REQUEST_TEMPLATE.md", "README.md");
        assertThat(PolicyDocumentPath.SUPPLEMENTARY_PATHS)
                .as("판정과 무관한 문서의 5xx 하나가 저장소를 통째로 보류시키면 안 된다")
                .noneMatch(PolicyDocumentPath::isRequired);
    }
}
