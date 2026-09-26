package com.ossagent.repository.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.domain.ContributionNotAllowedException;
import com.ossagent.repository.domain.ExcludedPathReason;
import com.ossagent.repository.domain.FakeRepositorySource;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryContext;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryMetadata;
import com.ossagent.repository.domain.RepositoryTree;
import com.ossagent.repository.domain.RepositoryTreeEntry;
import com.ossagent.repository.domain.SelectedFile;
import com.ossagent.repository.domain.SelectionReason;
import com.ossagent.support.github.GitHubRateLimitException;
import com.ossagent.support.github.GitHubUnreadableContentException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 저장소 분석의 업무 흐름 — 이슈 #15.
 *
 * <p>대역은 <b>능력 소비자 층</b>이다(Q-9) — {@code FakeRepositorySource} 가 트리·파일·실패 모드를
 * 재현한다. 어댑터 매핑은 {@code GitHubRepositorySourceTest} 가 따로 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BuildRepositoryContextUseCaseTest {

    private static final long REPO_ID = 7L;
    private static final String REF = "main";
    private static final RepositoryCoordinates REPO =
            new RepositoryCoordinates("spring-projects", "spring-kafka");

    private static final String SOURCE_PATH =
            "spring-kafka/src/main/java/org/springframework/kafka/listener/KafkaMessageListenerContainer.java";
    private static final String TEST_PATH =
            "spring-kafka/src/test/java/org/springframework/kafka/listener/KafkaMessageListenerContainerTests.java";

    private FakeRepositorySource source;
    private OssRepositoryRepository repositories;
    private AnalyzeRepositoryPolicyUseCase policyGate;
    private BuildRepositoryContextUseCase useCase;

    @BeforeEach
    void setUp() {
        // 스프링 컨텍스트가 아니라 매 테스트가 새 인스턴스다 — 누적 기록이 섞이지 않는다
        source = new FakeRepositorySource();
        source.given(new RepositoryMetadata(REPO, REF, "Java", false, false, 0));

        repositories = mock(OssRepositoryRepository.class);
        given(repositories.findById(REPO_ID)).willReturn(Optional.of(ossRepository()));

        // 기본은 「기여 허용」이다 — 막는 경우만 테스트가 따로 세운다
        policyGate = mock(AnalyzeRepositoryPolicyUseCase.class);

        useCase = new BuildRepositoryContextUseCase(repositories, policyGate, source,
                RepositoryContextProperties.defaults());
    }

    // ── S-5 · S-4 게이트 ──────────────────────────────────────────────────────

    @Test
    void 기여가_허용되지_않은_저장소는_파일을_한_건도_읽지_않는다_S5() {
        willThrow(new ContributionNotAllowedException(REPO_ID,
                ContributionNotAllowedException.Reason.UNDETERMINED))
                .given(policyGate).assertContributionAllowed(REPO_ID);
        source.givenTree(REPO, REF, SOURCE_PATH);

        assertThatThrownBy(() -> useCase.build(issue("KafkaMessageListenerContainer 버그", "")))
                .isInstanceOf(ContributionNotAllowedException.class);

        assertThat(source.fetchedTreeRefs())
                .as("보류(UNDETERMINED)도 금지와 같이 막는다 — 대상 저장소 소스를 끌어오기 전에 선다")
                .isEmpty();
        assertThat(source.fetchedPaths()).isEmpty();
    }

    @Test
    void 시크릿_경로는_읽지_않고_배제한다_S4() {
        // 🔴 샘플의 대표성 — 셋 다 SecretFilePolicy 에 실제로 물리는 모양이어야 한다.
        //    물리지 않는 샘플로 초록을 받으면 이 가드는 공허하다
        source.givenTree(REPO, REF,
                SOURCE_PATH,
                "spring-kafka/.env",
                "spring-kafka/deploy/id_rsa",
                "spring-kafka/config/credentials.json");
        source.givenFile(REPO, SOURCE_PATH, "class KafkaMessageListenerContainer {}");

        RepositoryContext context = useCase.build(
                issue("KafkaMessageListenerContainer 가 멈춘다", "`.env` 와 `credentials.json` 도 확인해 주세요"));

        assertThat(source.fetchedPaths())
                .as("경로 배제는 「가리기」가 아니라 「애초에 열지 않기」다 — fetch 자체가 없어야 한다")
                .doesNotContain("spring-kafka/.env", "spring-kafka/deploy/id_rsa",
                        "spring-kafka/config/credentials.json");
        assertThat(context.files()).extracting(SelectedFile::path).containsExactly(SOURCE_PATH);
    }

    @Test
    void 선별된_파일의_내용은_스크럽을_거친다_S4() {
        // 🔴 경로 정책을 정상 통과하는 소스에 토큰이 하드코딩된 경우 — SecretFilePolicy 가
        //    막지 못하는 바로 그 자리다. 토큰 「모양」이 유의미한 테스트라 조립한다
        //    (testing-philosophy.md 픽스처 규약의 명시적 예외)
        String hardcodedToken = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8";
        source.givenTree(REPO, REF, SOURCE_PATH);
        source.givenFile(REPO, SOURCE_PATH,
                "class KafkaMessageListenerContainer { String t = \"" + hardcodedToken + "\"; }");

        RepositoryContext context =
                useCase.build(issue("KafkaMessageListenerContainer 수정", ""));

        assertThat(context.files()).hasSize(1);
        assertThat(context.files().get(0).content())
                .as("경로 배제가 내용 스크럽을 대신하지 않는다 — 둘은 순서가 다른 방어다")
                .doesNotContain(hardcodedToken)
                .contains("***REDACTED***");
    }

    @Test
    void 선별된_파일을_찍어도_내용이_나가지_않는다_S4() {
        SelectedFile file = new SelectedFile("a/B.java", SelectionReason.TYPE_NAME, 10,
                "매우 비밀스러운 본문");

        assertThat(file.toString())
                .as("record 기본 toString 이면 로그 한 줄로 파일 본문이 통째로 나간다")
                .doesNotContain("매우 비밀스러운 본문")
                .contains("path=a/B.java");
    }

    // ── 선별 ──────────────────────────────────────────────────────────────────

    @Test
    void 이슈가_짚은_타입의_테스트_짝을_함께_가져온다() {
        source.givenTree(REPO, REF, SOURCE_PATH, TEST_PATH);
        source.givenFile(REPO, SOURCE_PATH, "class KafkaMessageListenerContainer {}");
        source.givenFile(REPO, TEST_PATH, "class KafkaMessageListenerContainerTests {}");

        RepositoryContext context =
                useCase.build(issue("KafkaMessageListenerContainer 가 멈춘다", ""));

        assertThat(context.files()).extracting(SelectedFile::path)
                .as("규약이 테스트를 요구하는 저장소가 많다 — 테스트 관습을 못 보면 계획이 어긋난다 (S-5)")
                .contains(TEST_PATH);
    }

    @Test
    void 테스트_짝이_직접_걸리지_않으면_짝_관계가_사유로_남는다() {
        // 이슈가 경로만 짚었다. Poller 는 대문자가 하나뿐이라 타입 이름으로 잡히지 않고,
        // 테스트 파일은 낱말 매칭(약함)으로만 걸린다 — 그때 「Poller 의 테스트라서」가
        // 「경로에 poller 라는 글자가 있어서」보다 나은 설명이다 (FR-5)
        String source = "src/main/java/org/x/Poller.java";
        String test = "src/test/java/org/x/PollerTests.java";
        this.source.givenTree(REPO, REF, source, test);
        this.source.givenFile(REPO, source, "class Poller {}");
        this.source.givenFile(REPO, test, "class PollerTests {}");

        RepositoryContext context =
                useCase.build(issue("수정 필요", "`src/main/java/org/x/Poller.java` 를 봐 주세요"));

        assertThat(context.files())
                .filteredOn(file -> file.path().equals(test))
                .extracting(SelectedFile::reason)
                .containsExactly(SelectionReason.TEST_PAIR);
    }

    @Test
    void 좁힐_신호가_없으면_빈_컨텍스트가_나온다() {
        source.givenTree(REPO, REF, SOURCE_PATH);

        RepositoryContext context = useCase.build(
                issue("동작이 이상합니다", "가끔 느려집니다. 확인 부탁드립니다."));

        assertThat(context.isEmpty())
                .as("억지로 채우면 #16 이 엉뚱한 파일로 계획을 세운다 — 비어 있는 것이 정직한 답이다")
                .isTrue();
        assertThat(source.fetchedPaths()).isEmpty();
    }

    @Test
    void 파일_수_상한을_넘으면_절단을_표시한다() {
        String[] paths = new String[5];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = "src/main/java/org/x/KafkaTemplate" + i + ".java";
        }
        source.givenTree(REPO, REF, paths);
        for (String path : paths) {
            source.givenFile(REPO, path, "class C {}");
        }
        useCase = withProperties(2, 120_000, 40_000);

        RepositoryContext context = useCase.build(issue("KafkaTemplate 문제", ""));

        assertThat(context.files()).hasSize(2);
        assertThat(context.budget().truncated())
                .as("조용히 멈추면 하류가 「관련 파일은 이게 전부」로 읽는다 (FR-4)")
                .isTrue();
        assertThat(context.excludedCount(ExcludedPathReason.BUDGET_EXHAUSTED)).isPositive();
    }

    @Test
    void 파일당_상한을_넘으면_잘라_넣지_않고_버린다() {
        source.givenTree(REPO, REF, SOURCE_PATH);
        source.givenFile(REPO, SOURCE_PATH, "가".repeat(200));
        useCase = withProperties(12, 120_000, 100);

        RepositoryContext context =
                useCase.build(issue("KafkaMessageListenerContainer 수정", ""));

        assertThat(context.files())
                .as("잘린 소스는 모델을 헷갈리게 한다 — 「절반이라도 낫다」가 성립하지 않는 자료형이다")
                .isEmpty();
        assertThat(context.excludedCount(ExcludedPathReason.TOO_LARGE)).isEqualTo(1);
        assertThat(context.budget().truncated()).isTrue();
    }

    // ── 실패 모드 ─────────────────────────────────────────────────────────────

    @Test
    void 파일_하나가_404_여도_나머지를_계속_모은다() {
        String other = "src/main/java/org/x/KafkaTemplate.java";
        source.givenTree(REPO, REF, SOURCE_PATH, other);
        source.givenFile(REPO, other, "class KafkaTemplate {}");
        // SOURCE_PATH 는 givenFile 하지 않는다 — 트리에는 있고 읽으면 없다

        RepositoryContext context = useCase.build(
                issue("KafkaMessageListenerContainer 와 KafkaTemplate", ""));

        assertThat(context.files()).extracting(SelectedFile::path).containsExactly(other);
        assertThat(context.excludedCount(ExcludedPathReason.NOT_FOUND)).isEqualTo(1);
    }

    @Test
    void 파일_하나를_못_읽어도_단계_전체가_실패하지_않는다() {
        source.givenTree(REPO, REF, SOURCE_PATH);
        source.failFileWith(SOURCE_PATH, new GitHubUnreadableContentException("1MB 초과"));

        RepositoryContext context =
                useCase.build(issue("KafkaMessageListenerContainer 수정", ""));

        assertThat(context.excludedCount(ExcludedPathReason.UNREADABLE))
                .as("「없다」(NOT_FOUND)와 「있는데 못 읽었다」를 같은 이름으로 부르지 않는다")
                .isEqualTo(1);
        assertThat(context.isEmpty()).isTrue();
    }

    @Test
    void 레이트리밋은_삼키지_않고_전파한다() {
        source.givenTree(REPO, REF, SOURCE_PATH);
        source.failFileWith(SOURCE_PATH, new GitHubRateLimitException(403,
                GitHubRateLimitException.Scope.PRIMARY, Instant.parse("2026-09-26T01:00:00Z"),
                Duration.ofMinutes(30), "리밋"));

        assertThatThrownBy(() -> useCase.build(issue("KafkaMessageListenerContainer 수정", "")))
                .as("리밋은 파일의 문제가 아니라 우리 호출 전체의 문제다 — 호출자가 「지연」으로 번역한다")
                .isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    void 트리가_잘리면_표시하되_실패시키지_않는다() {
        source.givenTree(REPO, REF, new RepositoryTree("sha", List.of(
                new RepositoryTreeEntry(SOURCE_PATH, RepositoryTreeEntry.EntryType.BLOB, 0)), true));
        source.givenFile(REPO, SOURCE_PATH, "class KafkaMessageListenerContainer {}");

        RepositoryContext context =
                useCase.build(issue("KafkaMessageListenerContainer 수정", ""));

        assertThat(context.treeTruncated()).isTrue();
        assertThat(context.isPartial())
                .as("관련 파일을 못 보는 것은 되돌릴 수 있는 실패다 — 규약 판정(S-5)과 방향이 다르다")
                .isTrue();
        assertThat(context.files()).hasSize(1);
    }

    @Test
    void 심볼릭링크와_서브모듈은_후보가_되지_않는다() {
        source.givenTree(REPO, REF, new RepositoryTree("sha", List.of(
                new RepositoryTreeEntry(SOURCE_PATH, RepositoryTreeEntry.EntryType.OTHER, 0)),
                false));

        RepositoryContext context =
                useCase.build(issue("KafkaMessageListenerContainer 수정", ""));

        assertThat(source.fetchedPaths())
                .as("읽을 수 없는 경로를 후보로 올리면 호출 예산만 태우고 예외 처리까지 탄다")
                .isEmpty();
        assertThat(context.isEmpty()).isTrue();
    }

    // ── 도우미 ───────────────────────────────────────────────────────────────

    private BuildRepositoryContextUseCase withProperties(int maxFiles, int maxTotalChars,
            int maxFileChars) {
        return new BuildRepositoryContextUseCase(repositories, policyGate, source,
                new RepositoryContextProperties(maxFiles, maxTotalChars, maxFileChars, 40, false));
    }

    private static AnalyzableIssue issue(String title, String body) {
        return new AnalyzableIssue(1L, REPO_ID, 42, title, body, List.of(),
                "https://github.com/spring-projects/spring-kafka/issues/42",
                FilterOutcome.PASSED, (short) 0);
    }

    /**
     * UseCase 가 이 엔티티에서 쓰는 것은 {@code owner}·{@code name} 뿐이다 —
     * 식별자는 {@code findById} 의 <b>키</b>로만 쓰이므로 심을 필요가 없다.
     */
    private static OssRepository ossRepository() {
        return new OssRepository("spring-projects", "spring-kafka",
                "https://github.com/spring-projects/spring-kafka");
    }
}
