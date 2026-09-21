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
    @DisplayName("빈 파일은 정상이다 — 크기 0 이면 내용도 비어 있는 게 맞다")
    void 진짜_빈_파일은_정상이다() {
        server.expect(once(), requestTo(BASE_URL + "/repos/spring-projects/spring-kafka/contents/EMPTY"))
                .andRespond(withSuccess("{\"type\":\"file\",\"encoding\":\"base64\",\"content\":\"\",\"size\":0}",
                        MediaType.APPLICATION_JSON));

        Optional<RepositoryFile> file = source.fetchFile(KAFKA, "EMPTY", null);

        assertThat(file).isPresent();
        assertThat(file.get().isEmpty()).isTrue();
    }

    private static String contentsJson(String content, int size) {
        String encoded = Base64.getMimeEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8));
        return """
                {"type":"file","encoding":"base64","size":%d,"content":"%s"}
                """.formatted(size, encoded.replace("\n", "\\n").replace("\r", ""));
    }
}
