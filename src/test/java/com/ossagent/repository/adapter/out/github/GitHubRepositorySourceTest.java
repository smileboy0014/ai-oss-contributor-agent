package com.ossagent.repository.adapter.out.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryFile;
import com.ossagent.repository.domain.RepositoryMetadata;
import com.ossagent.repository.domain.RepositoryTree;
import com.ossagent.repository.domain.RepositoryTreeEntry;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubErrorTranslator;
import com.ossagent.support.github.GitHubPermissionException;
import com.ossagent.support.github.GitHubProperties;
import com.ossagent.support.github.GitHubUnreadableContentException;
import com.ossagent.support.github.StaticTokenCredentials;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubRepositorySourceTest {

    private static final String BASE_URL = "https://api.github.test";
    private static final RepositoryCoordinates KAFKA =
            new RepositoryCoordinates("spring-projects", "spring-kafka");

    private MockRestServiceServer server;
    private GitHubRepositorySource source;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T09:00:00Z"), ZoneOffset.UTC);
        GitHubProperties properties = new GitHubProperties(BASE_URL, "", Duration.ofSeconds(1),
                Duration.ofSeconds(1), 0, Duration.ZERO, 100);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(),
                StaticTokenCredentials.from(properties), properties,
                new GitHubErrorTranslator(clock), clock);
        source = new GitHubRepositorySource(client);
    }

    @Test
    @DisplayName("저장소 메타데이터를 도메인 값으로 매핑한다")
    void 메타데이터를_매핑한다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/spring-projects/spring-kafka"))
                .andRespond(withSuccess("""
                        {
                          "default_branch": "main",
                          "language": "Java",
                          "archived": false,
                          "fork": false,
                          "open_issues_count": 42
                        }
                        """, MediaType.APPLICATION_JSON));

        RepositoryMetadata metadata = source.fetchMetadata(KAFKA);

        assertThat(metadata.coordinates()).isEqualTo(KAFKA);
        assertThat(metadata.defaultBranch()).isEqualTo("main");
        assertThat(metadata.language()).isEqualTo("Java");
        assertThat(metadata.openIssueCount()).isEqualTo(42);
        assertThat(metadata.acceptsContributions()).isTrue();
    }

    @Test
    @DisplayName("보관된 저장소는 기여 대상이 아니다")
    void 보관된_저장소를_알아본다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/spring-projects/spring-kafka"))
                .andRespond(withSuccess("{\"archived\":true}", MediaType.APPLICATION_JSON));

        assertThat(source.fetchMetadata(KAFKA).acceptsContributions())
                .as("보관 저장소는 PR 을 받지 않는다. 마지막 단계에서야 실패하면 파이프라인 전체가 낭비된다")
                .isFalse();
    }

    @Test
    @DisplayName("파일 내용을 base64 에서 디코드한다")
    void 파일을_디코드한다() {
        String content = "# Contributing\n\nDCO sign-off 가 필요합니다.";
        server.expect(once(),
                requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/contents/CONTRIBUTING.md"))
                .andRespond(withSuccess(contentsJson(content, content.getBytes(StandardCharsets.UTF_8).length),
                        MediaType.APPLICATION_JSON));

        Optional<RepositoryFile> file = source.fetchFile(KAFKA, "CONTRIBUTING.md", null);

        assertThat(file).isPresent();
        assertThat(file.get().content()).isEqualTo(content);
        assertThat(file.get().path()).isEqualTo("CONTRIBUTING.md");
    }

    @Test
    @DisplayName("ref 를 주면 그 시점을 본다")
    void ref를_전달한다() {
        server.expect(once(), requestTo(
                BASE_URL + "/repos/spring-projects/spring-kafka/contents/build.gradle?ref=3.2.x"))
                .andRespond(withSuccess(contentsJson("plugins {}", 10), MediaType.APPLICATION_JSON));

        assertThat(source.fetchFile(KAFKA, "build.gradle", "3.2.x")).isPresent();

        server.verify();
    }

    @Test
    @DisplayName("404 만 「파일 없음」이다 — 규약이 없는 저장소는 정상이다")
    void 없는_파일은_빈_값이다() {
        server.expect(once(),
                requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/contents/AGENTS.md"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"message\":\"Not Found\"}"));

        assertThat(source.fetchFile(KAFKA, "AGENTS.md", null)).isEmpty();
    }

    @Test
    @DisplayName("권한 오류는 「파일 없음」이 아니다 — 「보류」로 넘겨야 한다")
    void 권한오류는_빈_값이_아니다_S5() {
        server.expect(once(),
                requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/contents/CONTRIBUTING.md"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"message\":\"Resource not accessible\"}"));

        assertThatThrownBy(() -> source.fetchFile(KAFKA, "CONTRIBUTING.md", null))
                .as("읽지 못한 것을 「규약 없음」으로 읽으면 규약 위반 PR 이 나간다 — S-5")
                .isInstanceOf(GitHubPermissionException.class);
    }

    @Test
    @DisplayName("1MB 초과 파일은 조용한 빈 문자열이 아니라 예외다")
    void 인라인_한계_초과는_예외다_S5() {
        // Contents API 는 1MB 를 넘으면 content 를 비워서 준다
        server.expect(once(),
                requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/contents/CONTRIBUTING.md"))
                .andRespond(withSuccess(
                        "{\"type\":\"file\",\"encoding\":\"none\",\"content\":\"\",\"size\":2097152}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.fetchFile(KAFKA, "CONTRIBUTING.md", null))
                .as("빈 내용을 「빈 파일」로 읽으면 규약이 있는데도 「규약 없음 → 허용」이 된다 — S-5")
                .isInstanceOf(GitHubUnreadableContentException.class)
                .hasMessageContaining("blob/raw");
    }

    @Test
    @DisplayName("경로가 디렉터리면 예외다")
    void 디렉터리는_예외다_S5() {
        server.expect(once(), requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/contents/.github"))
                .andRespond(withSuccess("[{\"type\":\"file\",\"name\":\"CONTRIBUTING.md\"}]",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.fetchFile(KAFKA, ".github", null))
                .isInstanceOf(GitHubUnreadableContentException.class);
    }

    @Test
    @DisplayName("심볼릭링크·서브모듈은 파일이 아니다")
    void 일반_파일이_아니면_예외다_S5() {
        server.expect(once(), requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/contents/link"))
                .andRespond(withSuccess("{\"type\":\"symlink\",\"size\":12}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.fetchFile(KAFKA, "link", null))
                .isInstanceOf(GitHubUnreadableContentException.class);
    }

    @Test
    @DisplayName("size 가 빠져도 encoding 이 base64 가 아니면 예외다 — size 에만 기대지 않는다")
    void size가_없어도_encoding으로_읽지_못함을_알아낸다_S5() {
        // GitHub 이 1MB 초과에 쓰는 실제 표식은 encoding:"none" 이다.
        // size 에만 판정을 걸면 size 가 빠진 응답에서 「읽지 못함」이 「빈 파일」로 샌다
        server.expect(once(),
                requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/contents/CONTRIBUTING.md"))
                .andRespond(withSuccess("{\"type\":\"file\",\"encoding\":\"none\",\"content\":\"\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.fetchFile(KAFKA, "CONTRIBUTING.md", null))
                .as("「읽지 못했다」가 「규약이 없다」로 번역되면 규약 위반 PR 이 나간다 — S-5")
                .isInstanceOf(GitHubUnreadableContentException.class)
                .hasMessageContaining("blob/raw");
    }

    @Test
    @DisplayName("encoding 필드가 아예 없어도 「읽지 못함」이다 — 필드의 부재를 신뢰하지 않는다")
    void encoding이_없으면_읽지_못한_것이다_S5() {
        // 「읽었다」는 base64 로 긍정적으로 증명될 때만 참이다.
        // encoding·content·size 가 모두 빠진 200 응답이 빈 파일로 귀결되면
        // 「CONTRIBUTING.md 가 있는데 비었다」가 되고, #7 이 「규약 없음 → 허용」으로 읽는다
        server.expect(once(),
                requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/contents/CONTRIBUTING.md"))
                .andRespond(withSuccess("{\"type\":\"file\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.fetchFile(KAFKA, "CONTRIBUTING.md", null))
                .as("판정 세 개를 모두 빠져나가는 조합이 남아 있으면 S-5 가 뚫린다")
                .isInstanceOf(GitHubUnreadableContentException.class);
    }

    @Test
    @DisplayName("빈 파일은 정상이다 — 크기 0 이면 내용도 비어 있는 게 맞다")
    void 진짜_빈_파일은_정상이다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/contents/EMPTY"))
                .andRespond(withSuccess("{\"type\":\"file\",\"encoding\":\"base64\",\"content\":\"\",\"size\":0}",
                        MediaType.APPLICATION_JSON));

        Optional<RepositoryFile> file = source.fetchFile(KAFKA, "EMPTY", null);

        assertThat(file).isPresent();
        assertThat(file.get().isEmpty()).isTrue();
    }

    // ── 트리 조회 (#15) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("트리를 recursive 로 한 번에 읽고 blob 만 파일로 본다")
    void 트리를_매핑한다() {
        server.expect(once(), requestTo(
                        BASE_URL + "/repos/spring-projects/spring-kafka/git/trees/main?recursive=1"))
                .andRespond(withSuccess("""
                        {
                          "sha": "tree-sha-1",
                          "truncated": false,
                          "tree": [
                            {"path": "src", "type": "tree", "mode": "040000"},
                            {"path": "src/main/java/A.java", "type": "blob", "mode": "100644", "size": 120},
                            {"path": "libs/vendor", "type": "commit", "mode": "160000"},
                            {"path": "docs/link.md", "type": "blob", "mode": "120000", "size": 12}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        RepositoryTree tree = source.fetchTree(KAFKA, "main");

        assertThat(tree.sha()).isEqualTo("tree-sha-1");
        assertThat(tree.blobs()).extracting(RepositoryTreeEntry::path)
                .as("서브모듈(commit)과 심볼릭링크(mode 120000)는 읽을 수 없다 — blob 으로 세면 예산만 태운다")
                .containsExactly("src/main/java/A.java");
        assertThat(tree.blobs().get(0).size()).isEqualTo(120);
    }

    @Test
    @DisplayName("트리가 잘려 오면 그 사실을 값으로 들고 나온다 — 실패가 아니다")
    void 잘린_트리를_표시한다() {
        server.expect(once(), requestTo(
                        BASE_URL + "/repos/spring-projects/spring-kafka/git/trees/main?recursive=1"))
                .andRespond(withSuccess(
                        "{\"sha\":\"s\",\"truncated\":true,\"tree\":[]}", MediaType.APPLICATION_JSON));

        RepositoryTree tree = source.fetchTree(KAFKA, "main");

        assertThat(tree.truncated())
                .as("못 받은 경로는 「없는 것」이 아니라 「못 본 것」이다")
                .isTrue();
    }

    @Test
    @DisplayName("tree 배열이 없으면 빈 트리가 아니라 예외다")
    void 모양이_다른_응답을_빈_트리로_읽지_않는다() {
        server.expect(once(), requestTo(
                        BASE_URL + "/repos/spring-projects/spring-kafka/git/trees/main?recursive=1"))
                .andRespond(withSuccess("{\"sha\":\"s\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> source.fetchTree(KAFKA, "main"))
                .as("빈 트리로 옮기면 선별이 조용히 0건이 되고 원인이 드러나지 않는다")
                .isInstanceOf(GitHubUnreadableContentException.class);
    }

    @Test
    @DisplayName("ref 를 주지 않으면 기본 브랜치를 알아내 쓴다")
    void ref_가_없으면_기본_브랜치를_해석한다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/spring-projects/spring-kafka"))
                .andRespond(withSuccess("{\"default_branch\":\"develop\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
                        BASE_URL + "/repos/spring-projects/spring-kafka/git/trees/develop?recursive=1"))
                .andRespond(withSuccess(
                        "{\"sha\":\"s\",\"truncated\":false,\"tree\":[]}", MediaType.APPLICATION_JSON));

        assertThat(source.fetchTree(KAFKA, null).sha()).isEqualTo("s");
        server.verify();
    }

    private static String contentsJson(String content, int size) {
        String encoded = Base64.getMimeEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8));
        return """
                {"type":"file","encoding":"base64","size":%d,"content":"%s"}
                """.formatted(size, encoded.replace("\n", "\\n").replace("\r", ""));
    }
}
