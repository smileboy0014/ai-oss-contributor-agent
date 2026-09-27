package com.ossagent.pullrequest.adapter.out.github;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    /**
     * {@code POST /repos/{upstream}/pulls} 의 본문 — 🔴 <b>S-2 의 실행체</b>.
     *
     * <h2>🔴 {@code draft} 가 필드가 아니다</h2>
     *
     * <p>{@code boolean draft} 를 컴포넌트로 두면 {@code false} 를 담은 인스턴스가
     * <b>표현 가능</b>해진다. {@code safety-boundaries.md} 가 「플래그를 두면 언젠가 켜진다」고
     * 못 박은 그것이다. 여기서는 <b>상수를 돌려주는 접근자</b>라 담을 자리가 없다 —
     * {@code PullRequest.Status} 가 {@code DRAFT} 하나뿐인 것과 같은 수법이다.
     *
     * <p>⚠️ <b>record 가 아니라 클래스인 것이 의도다.</b> record 로 두면 Jackson 이
     * 컴포넌트를 기준으로 직렬화하므로 「컴포넌트가 아닌 접근자」가 포함되는지가
     * <b>기본 동작에 달린다.</b> 일반 클래스의 getter 는 그런 조건이 없다.
     *
     * <h2>🔴 그럼에도 애노테이션을 단다 — 「기본값이 그렇다」에 기대지 않는다</h2>
     *
     * <p>안전 리뷰가 짚은 자리다. 직렬화 회귀 테스트는 {@code new ObjectMapper()} 와
     * {@code RestClient.builder()} 의 <b>기본 컨버터</b>로 돈다. 지금은 운영도 같은
     * 경로다({@code GitHubClientConfig} 가 {@code RestClient.builder()} 를 쓰고
     * {@code spring.jackson.*} 설정이 없다). <b>그 둘이 갈라지는 날</b> — 누가 Boot 가
     * 조립한 {@code RestClient.Builder} 빈을 쓰거나 {@code default-property-inclusion}·
     * {@code visibility} 를 건드리면 — <b>테스트는 초록인 채 운영에서만 {@code draft} 가
     * 빠진다.</b>
     *
     * <p>그리고 빠지는 방식이 나쁘다: 필드가 아니라 <b>조용히 사라지는</b> 쪽이라
     * 예외가 나지 않고, GitHub 은 그것을 「draft 아님」으로 읽는다.
     * {@link JsonProperty} 가 이름과 포함을, {@link JsonInclude.Include#ALWAYS} 가
     * 전역 inclusion 설정을 각각 못 박는다. <b>필드를 되살리는 것이 아니므로</b>
     * {@code false} 는 여전히 표현 불가능하다.
     *
     * <p>⚠️ 응답 검증({@code GitHubDraftPrPublisher} 가 {@code draft} 가 아니면 던진다)이
     * 마지막 안전망이지만, 그것이 발화하는 시점에는 <b>이미 PR 이 만들어진 뒤</b>다.
     *
     * <p>⚠️ 필드를 더할 때 {@code reviewers}·{@code assignees}·{@code labels} 를 넣지 않는다 —
     * 리뷰어 지정은 <b>존재 자체가 반려</b>다 (S-2).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    static final class DraftPullRequestRequest {

        private final String title;
        private final String head;
        private final String base;
        private final String body;

        DraftPullRequestRequest(String title, String head, String base, String body) {
            this.title = title;
            this.head = head;
            this.base = base;
            this.body = body;
        }

        public String getTitle() {
            return title;
        }

        /** {@code owner:branch} — 교차 저장소 PR 의 head 표기. */
        public String getHead() {
            return head;
        }

        public String getBase() {
            return base;
        }

        public String getBody() {
            return body;
        }

        /**
         * 🔴 <b>리터럴이다.</b> 필드도 파라미터도 설정도 아니다 — S-2.
         *
         * <p>{@link JsonProperty} 는 이름을 못 박는 동시에 <b>getter 가시성 설정과 무관하게</b>
         * 포함되게 한다. 기본 동작에 기대지 않는 이유는 클래스 javadoc 에 있다.
         */
        @JsonProperty("draft")
        public boolean isDraft() {
            return true;
        }

        /** 🔴 제목·본문을 노출하지 않는다 (S-4). */
        @Override
        public String toString() {
            return "DraftPullRequestRequest[head=%s, base=%s, draft=true, body=%d자]"
                    .formatted(head, base, body == null ? 0 : body.length());
        }
    }
}
