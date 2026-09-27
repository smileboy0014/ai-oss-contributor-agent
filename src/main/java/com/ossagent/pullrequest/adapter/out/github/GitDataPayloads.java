package com.ossagent.pullrequest.adapter.out.github;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ossagent.pullrequest.domain.CommitIdentity;
import com.ossagent.pullrequest.domain.FileChange;
import java.time.Instant;
import java.util.List;

/**
 * Git Data API 요청 본문 — <b>이 패키지 밖으로 나가지 않는다</b>.
 *
 * <p>외부 스키마를 domain 이 알면 도메인이 GitHub 에 묶인다 — {@code architecture.md}
 * 「GitHub 응답 파싱은 adapter/out 에서 끝낸다」.
 *
 * <h2>🔴 {@code sha: null} 이 JSON 에 실제로 실려야 한다</h2>
 *
 * <p>{@code base_tree} 를 준 상태에서 tree 항목의 {@code sha} 를 {@code null} 로 두면
 * 그 경로가 <b>삭제</b>된다. 그런데 Jackson 이 {@code NON_NULL} 로 설정돼 있으면 필드가
 * <b>통째로 빠지고</b>, GitHub 은 그것을 「이 항목을 건드리지 않음」으로 읽어
 * <b>삭제가 조용히 누락</b>된다.
 *
 * <p>실패가 예외가 아니라 <b>「diff 가 조용히 다르다」</b>로 나타나므로, 전역 Jackson 설정에
 * 기대지 않고 {@link JsonInclude.Include#ALWAYS} 를 <b>이 타입에 명시</b>한다.
 * 테스트는 요청 본문 문자열에 {@code "sha":null} 이 실재하는지를 본다.
 */
final class GitDataPayloads {

    private GitDataPayloads() {
    }

    /** {@code POST /git/blobs} */
    record BlobRequest(String content, String encoding) {

        static BlobRequest utf8(String content) {
            return new BlobRequest(content, "utf-8");
        }
    }

    /**
     * {@code POST /git/trees} 의 항목 하나.
     *
     * <p>🔴 {@code ALWAYS} 가 이 타입의 전부다. 지우면 삭제가 동작하지 않는다.
     *
     * @param path 저장소 루트 기준 경로
     * @param mode {@code 100644} · {@code 100755}
     * @param type 항상 {@code blob}
     * @param sha  blob sha. 🔴 <b>{@code null} 이면 삭제</b>이고, 그 {@code null} 이 직렬화돼야 한다
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record TreeEntry(String path, String mode, String type, String sha) {

        static TreeEntry blob(FileChange change, String sha) {
            return new TreeEntry(change.path(), change.mode(), "blob", sha);
        }

        /** 🔴 삭제. {@code sha} 가 {@code null} 이고 그대로 실려야 한다. */
        static TreeEntry deletion(FileChange change) {
            return new TreeEntry(change.path(), change.mode(), "blob", null);
        }
    }

    /** {@code POST /git/trees} */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record TreeRequest(String base_tree, List<TreeEntry> tree) {
    }

    /**
     * {@code POST /git/commits}
     *
     * <p>⚠️ {@code author}·{@code committer} 의 {@code date} 는 <b>커밋 해시에 들어간다.</b>
     * 재시도마다 시각을 다시 읽으면 같은 내용이 다른 sha 를 낳아 멱등이 깨진다 —
     * {@code GitHubForkPublisher} 가 진입 시 한 번 읽어 재사용한다.
     *
     * <p>🔴 <b>여기만 {@code NON_NULL} 이다 — {@link TreeEntry} 와 정반대이고 그것이 의도다.</b>
     * 서명자가 설정되지 않으면 {@code author} 를 <b>보내지 않아야</b> GitHub 이 토큰 소유자로
     * 채운다. {@code "author": null} 을 보내면 거부된다. tree 쪽은 {@code null} 자체가
     * 「삭제」라는 <b>의미</b>라 반드시 실려야 한다 — 같은 전역 설정으로 둘 다 만족시킬 수 없어
     * 타입마다 명시한다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CommitRequest(String message, String tree, List<String> parents,
                         Author author, Author committer) {

        record Author(String name, String email, String date) {

            static Author of(CommitIdentity identity, Instant at) {
                return new Author(identity.name(), identity.email(), at.toString());
            }
        }
    }

    /** {@code POST /git/refs} — 첫 생성. */
    record CreateRefRequest(String ref, String sha) {
    }

    /** {@code PATCH /git/refs/heads/{branch}} — 재시도 경로에서만 {@code force} 를 켠다. */
    record UpdateRefRequest(String sha, boolean force) {
    }

    /** {@code POST /merge-upstream} */
    record MergeUpstreamRequest(String branch) {
    }
}
