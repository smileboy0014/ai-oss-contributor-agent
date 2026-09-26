package com.ossagent.repository.adapter.out.github;

import com.ossagent.support.ExternalAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.repository.domain.RepositoryFile;
import com.ossagent.repository.domain.RepositoryMetadata;
import com.ossagent.repository.domain.RepositorySource;
import com.ossagent.repository.domain.RepositoryTree;
import com.ossagent.repository.domain.RepositoryTreeEntry;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubRequest;
import com.ossagent.support.github.GitHubResourceNotFoundException;
import com.ossagent.support.github.GitHubResponse;
import com.ossagent.support.github.GitHubUnreadableContentException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
@ExternalAdapter
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
        //
        //    ⚠ encoding 이 null(필드 자체가 없음)인 경우도 「읽지 못함」이다.
        //    「읽었다」는 base64 로 <b>긍정적으로 증명</b>될 때만 참이다. 필드의 부재를
        //    신뢰 가능한 상태로 취급하면 {"type":"file"} 뿐인 응답이 빈 파일로 귀결된다.
        //    진짜 빈 파일도 GitHub 은 encoding:"base64" + content:"" 로 준다.
        if (!"base64".equals(encoding)) {
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

    /**
     * {@code GET /repos/{owner}/{repo}/git/trees/{ref}?recursive=1} — 저장소 전체 경로를 1회로 받는다.
     *
     * <p>🔴 <b>빈 값이 없다.</b> 404 를 포함한 모든 실패가 예외로 나간다 —
     * {@link com.ossagent.repository.domain.RepositorySource#fetchTree} 의 계약이다.
     *
     * <p>⚠️ <b>{@code ref} 에 {@code /} 가 들어 있으면 보장하지 않는다.</b> 이 경로는
     * {@code UriBuilder} 가 조립하므로 {@code /} 가 경로 구분자로 나간다. Phase 1~3 대상
     * 저장소의 기본 브랜치는 전부 {@code main}·{@code master} 라 지금은 닿지 않고,
     * 슬래시 있는 브랜치를 실제로 넘길 일이 생기면 커밋 SHA 로 먼저 해석해야 한다.
     * 조용히 엉뚱한 트리를 받는 것보다 <b>한계를 적어 두는 쪽</b>을 택했다.
     */
    @Override
    public RepositoryTree fetchTree(RepositoryCoordinates coordinates, String ref) {
        String resolvedRef = resolveRef(coordinates, ref);
        GitHubResponse response = client.get(GitHubRequest
                .of("/repos/%s/%s/git/trees/%s".formatted(coordinates.owner(), coordinates.name(),
                        resolvedRef))
                .withQuery("recursive", "1"));

        if (!response.hasBody()) {
            throw new GitHubUnreadableContentException(
                    "트리 응답에 본문이 없습니다 repo=%s ref=%s"
                            .formatted(coordinates.fullName(), resolvedRef));
        }
        JsonNode body = response.body();
        JsonNode tree = body.path("tree");
        if (!tree.isArray()) {
            // 🔴 「항목 0개」가 아니라 「우리가 아는 모양이 아니다」다. 빈 트리로 옮기면
            //    선별이 조용히 0건이 되고 원인이 드러나지 않는다
            throw new GitHubUnreadableContentException(
                    "트리 응답에 tree 배열이 없습니다 repo=%s ref=%s"
                            .formatted(coordinates.fullName(), resolvedRef));
        }

        List<RepositoryTreeEntry> entries = new ArrayList<>(tree.size());
        for (JsonNode node : tree) {
            String path = text(node, "path");
            if (path == null || path.isBlank()) {
                continue;
            }
            entries.add(new RepositoryTreeEntry(
                    path,
                    // 🔴 mode 를 함께 넘긴다 — 심볼릭링크는 type 이 "blob" 이고 mode 로만 갈린다
                    RepositoryTreeEntry.EntryType.from(text(node, "type"), text(node, "mode")),
                    node.path("size").asInt(0)));
        }

        boolean truncated = bool(body, "truncated");
        if (truncated) {
            // ⚠️ 실패가 아니라 사실이다. 판단은 호출자가 한다 — RepositoryTree javadoc
            log.warn("대상 저장소 트리가 잘렸습니다 — 일부 경로를 보지 못합니다 repo={} ref={} entries={}",
                    coordinates.fullName(), resolvedRef, entries.size());
        }
        // 🔴 경로를 나열하지 않는다. 수천 건이고 대상 저장소의 임의 문자열이다
        log.debug("대상 저장소 트리 읽음 repo={} ref={} entries={} truncated={}",
                coordinates.fullName(), resolvedRef, entries.size(), truncated);
        return new RepositoryTree(text(body, "sha"), entries, truncated);
    }

    /**
     * {@code ref} 가 없으면 기본 브랜치를 쓴다 — {@code fetchFile} 과 같은 규칙.
     *
     * <p>⚠️ 이때 메타데이터 호출이 <b>1회 더</b> 든다. 호출자가 이미 기본 브랜치를 알고 있으면
     * 그 값을 넘겨 이 경로를 피하는 것이 낫다 — {@code BuildRepositoryContextUseCase} 가 그렇게 한다.
     */
    private String resolveRef(RepositoryCoordinates coordinates, String ref) {
        if (ref != null && !ref.isBlank()) {
            return ref.trim();
        }
        String defaultBranch = fetchMetadata(coordinates).defaultBranch();
        if (defaultBranch == null || defaultBranch.isBlank()) {
            // 「못 알아냈다」를 임의의 기본값(main)으로 메우지 않는다 — 엉뚱한 트리를 받는다
            throw new GitHubUnreadableContentException(
                    "기본 브랜치를 알 수 없어 트리를 읽지 못했습니다 repo=" + coordinates.fullName());
        }
        return defaultBranch;
    }

    private static String decode(String encoded, String encoding, RepositoryCoordinates coordinates,
            String path) {
        // 여기 도달하면 encoding 은 반드시 base64 다 — 호출자가 그 밖을 전부 걷어냈다.
        // 빈 내용은 「진짜 빈 파일」이다(GitHub 은 빈 파일도 base64 로 표시한다).
        if (encoded == null || encoded.isBlank()) {
            return "";
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
