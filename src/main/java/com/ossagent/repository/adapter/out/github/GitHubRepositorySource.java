package com.ossagent.repository.adapter.out.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryFile;
import com.ossagent.repository.domain.RepositoryMetadata;
import com.ossagent.repository.domain.RepositorySource;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubRequest;
import com.ossagent.support.github.GitHubResourceNotFoundException;
import com.ossagent.support.github.GitHubResponse;
import com.ossagent.support.github.GitHubUnreadableContentException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link RepositorySource} 의 GitHub 구현.
 *
 * <p>여기서 JSON → 도메인 값 매핑이 <b>끝난다.</b> 원시 {@code JsonNode} 나 GitHub 스키마가
 * application·domain 으로 올라가면 도메인이 외부 스키마에 묶인다 —
 * {@code .claude/rules/conventions/architecture.md} 「LLM·GitHub 응답 파싱은 adapter/out 에서 끝낸다」.
 *
 * <p>읽기만 한다. Fork 생성·push 는 이 어댑터에 없다 — S-1.
 */
@Component
public class GitHubRepositorySource implements RepositorySource {

    private static final Logger log = LoggerFactory.getLogger(GitHubRepositorySource.class);

    /** Contents API 가 파일 내용을 직접 실어 주는 한계. 넘으면 {@code content} 가 빈 문자열로 온다. */
    private static final int CONTENTS_API_INLINE_LIMIT_BYTES = 1024 * 1024;

    private final GitHubApiClient client;

    public GitHubRepositorySource(GitHubApiClient client) {
        this.client = client;
    }

    @Override
    public RepositoryMetadata fetchMetadata(RepositoryCoordinates coordinates) {
        GitHubResponse response = client.get(
                GitHubRequest.of("/repos/%s/%s".formatted(coordinates.owner(), coordinates.name())));
        if (!response.hasBody()) {
            throw new GitHubUnreadableContentException(
                    "저장소 메타데이터 응답에 본문이 없습니다 repo=" + coordinates.fullName());
        }
        JsonNode body = response.body();
        return new RepositoryMetadata(
                coordinates,
                text(body, "default_branch"),
                text(body, "language"),
                bool(body, "archived"),
                bool(body, "fork"),
                body.path("open_issues_count").asInt(0));
    }

    @Override
    public Optional<RepositoryFile> fetchFile(RepositoryCoordinates coordinates, String path,
            String ref) {
        GitHubRequest request = GitHubRequest
                .of("/repos/%s/%s/contents/%s".formatted(coordinates.owner(), coordinates.name(),
                        normalize(path)))
                .withQuery("ref", ref);

        GitHubResponse response;
        try {
            response = client.get(request);
        } catch (GitHubResourceNotFoundException e) {
            // 🔴 404 만 「없음」이다. 권한·레이트리밋은 위로 전파되어 호출자가 「보류」를 택한다 — S-5
            log.debug("대상 저장소 파일 없음 repo={} path={}", coordinates.fullName(), path);
            return Optional.empty();
        }

        if (!response.hasBody()) {
            throw new GitHubUnreadableContentException(
                    "파일 응답에 본문이 없습니다 repo=%s path=%s".formatted(coordinates.fullName(), path));
        }
        JsonNode body = response.body();

        // 디렉터리면 배열이 온다. symlink·submodule 은 type 이 다르고 content 가 없다.
        // 어느 쪽이든 「파일을 읽었다」가 아니므로 빈 값으로 돌려주지 않는다
        if (body.isArray()) {
            throw new GitHubUnreadableContentException(
                    "경로가 파일이 아니라 디렉터리입니다 repo=%s path=%s"
                            .formatted(coordinates.fullName(), path));
        }
        String type = text(body, "type");
        if (!"file".equals(type)) {
            throw new GitHubUnreadableContentException(
                    "경로가 일반 파일이 아닙니다 repo=%s path=%s type=%s"
                            .formatted(coordinates.fullName(), path, type));
        }

        String encoded = text(body, "content");
        String encoding = text(body, "encoding");
        int size = body.path("size").asInt(0);

        // 🔴 1MB 초과 파일은 Contents API 가 내용을 비우고 encoding 을 "none" 으로 준다.
        //    이것을 「빈 파일」로 읽으면 규약 파일이 실제로 있는데도 「규약 없음 → 허용」이 된다 — S-5.
        //
        //    판정을 size 에만 걸지 않는다. size 가 빠졌거나 0 으로 오는 응답에서
        //    「읽지 못함」이 조용히 「빈 파일」로 새기 때문이다. GitHub 이 쓰는 실제 표식인
        //    encoding 을 먼저 본다 — base64 가 아니면 우리가 읽을 수 있는 내용이 아니다.
        if (encoding != null && !"base64".equals(encoding)) {
            throw new GitHubUnreadableContentException(
                    ("파일 내용을 받지 못했습니다 — Contents API 인라인 한계(%d bytes)를 넘었을 수 있습니다. "
                            + "blob/raw API 가 필요합니다 repo=%s path=%s encoding=%s size=%d")
                            .formatted(CONTENTS_API_INLINE_LIMIT_BYTES, coordinates.fullName(), path,
                                    encoding, size));
        }
        if ((encoded == null || encoded.isBlank()) && size > 0) {
            throw new GitHubUnreadableContentException(
                    ("파일 내용이 비어 있는데 size 가 0 이 아닙니다 — 내용을 받지 못했습니다. "
                            + "repo=%s path=%s size=%d").formatted(coordinates.fullName(), path, size));
        }

        String content = decode(encoded, encoding, coordinates, path);
        // 🔴 내용을 로그에 찍지 않는다. 대상 저장소가 시크릿을 커밋해 뒀을 수 있다
        log.debug("대상 저장소 파일 읽음 repo={} path={} size={}", coordinates.fullName(), path, size);
        return Optional.of(new RepositoryFile(path, content));
    }

    private static String decode(String encoded, String encoding, RepositoryCoordinates coordinates,
            String path) {
        // 인코딩 검사는 호출자가 이미 끝냈다(여기 도달하면 encoding 은 null 이거나 base64 다).
        // 빈 내용을 인코딩 검사보다 먼저 통과시키면 「읽지 못함」이 「빈 파일」로 샌다 — S-5
        if (encoded == null || encoded.isBlank()) {
            return "";
        }
        if (encoding != null && !"base64".equals(encoding)) {
            throw new GitHubUnreadableContentException(
                    "알 수 없는 파일 인코딩입니다 repo=%s path=%s encoding=%s"
                            .formatted(coordinates.fullName(), path, encoding));
        }
        try {
            // GitHub 은 base64 를 줄바꿈으로 접어서 준다 — MIME 디코더를 쓴다
            return new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new GitHubUnreadableContentException(
                    "파일 내용을 디코드하지 못했습니다 repo=%s path=%s"
                            .formatted(coordinates.fullName(), path));
        }
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("파일 경로가 비어 있습니다");
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static boolean bool(JsonNode node, String field) {
        return node.path(field).asBoolean(false);
    }
}
