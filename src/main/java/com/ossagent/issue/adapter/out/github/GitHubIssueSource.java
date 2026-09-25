package com.ossagent.issue.adapter.out.github;

import com.ossagent.support.ExternalAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.ossagent.issue.domain.IssuePage;
import com.ossagent.issue.domain.IssueQuery;
import com.ossagent.issue.domain.IssueSnapshot;
import com.ossagent.issue.domain.IssueSource;
import com.ossagent.support.github.GitHubApiClient;
import com.ossagent.support.github.GitHubRequest;
import com.ossagent.support.github.GitHubResponse;
import com.ossagent.support.github.GitHubUnreadableContentException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link IssueSource} 의 GitHub 구현.
 *
 * <p>GitHub 이슈 목록 API 의 두 함정을 여기서 흡수한다.
 *
 * <ol>
 *   <li><b>PR 이 섞여 들어온다.</b> {@code pull_request} 필드 유무로 표시해 둔다 — 버리지 않고
 *       표시만 하는 이유는 호출자가 「PR 이 몇 건인가」를 필터 근거로 쓸 수 있어야 하기 때문이다(이슈 #9)
 *   <li><b>다음 페이지 여부가 본문이 아니라 {@code Link} 헤더에 있다.</b> 본문 길이로 추측하면
 *       마지막 페이지가 꽉 찬 경우에 빈 페이지를 한 번 더 부른다
 * </ol>
 */
@Component
@ExternalAdapter
public class GitHubIssueSource implements IssueSource {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueSource.class);

    private final GitHubApiClient client;

    public GitHubIssueSource(GitHubApiClient client) {
        this.client = client;
    }

    @Override
    public IssuePage fetchOpenIssues(IssueQuery query) {
        GitHubRequest request = GitHubRequest
                .of("/repos/%s/%s/issues".formatted(query.coordinates().owner(),
                        query.coordinates().name()))
                .withQuery("state", "open")
                .withQuery("sort", "updated")
                .withQuery("direction", "asc")
                .withQuery("per_page", String.valueOf(query.perPage()))
                .withQuery("page", String.valueOf(query.page()))
                .withQuery("since", query.updatedSince() == null ? null : query.updatedSince().toString())
                .withIfNoneMatch(query.etag());

        GitHubResponse response = client.get(request);

        if (response.notModified()) {
            // 「이슈가 없다」가 아니라 「직전 조회 이후 바뀐 것이 없다」다. 구분하지 않으면
            // 증분 수집이 기존 후보를 사라진 것으로 판단한다
            log.debug("대상 저장소 이슈 변화 없음 repo={} page={}", query.coordinates().fullName(), query.page());
            return IssuePage.unchanged(response.etag() == null ? query.etag() : response.etag());
        }
        if (!response.hasBody() || !response.body().isArray()) {
            throw new GitHubUnreadableContentException(
                    "이슈 목록 응답이 배열이 아닙니다 repo=" + query.coordinates().fullName());
        }

        List<IssueSnapshot> issues = new ArrayList<>();
        for (JsonNode node : response.body()) {
            issues.add(toSnapshot(node));
        }
        boolean hasNext = hasNextLink(response.linkHeader());

        if (log.isDebugEnabled()) {
            // 인자는 레벨과 무관하게 먼저 평가된다. 스캔의 모든 페이지에서 도는 자리라
            // 집계 스트림을 조건 안으로 넣는다
            log.debug("대상 저장소 이슈 조회 repo={} page={} 건수={} 이슈만={} hasNext={}",
                    query.coordinates().fullName(), query.page(), issues.size(),
                    issues.stream().filter(IssueSnapshot::isIssue).count(), hasNext);
        }
        return new IssuePage(issues, response.etag(), false, hasNext);
    }

    private static IssueSnapshot toSnapshot(JsonNode node) {
        return new IssueSnapshot(
                node.path("number").asInt(),
                text(node, "title"),
                text(node, "body"),
                labels(node),
                node.path("user").path("login").asText(null),
                node.path("comments").asInt(0),
                instant(node, "created_at"),
                instant(node, "updated_at"),
                // ⚠ 이슈 목록 API 는 PR 도 돌려준다. PR 에만 이 필드가 붙는다
                node.has("pull_request"));
    }

    private static List<String> labels(JsonNode node) {
        JsonNode labels = node.path("labels");
        if (!labels.isArray()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(labels.size());
        for (JsonNode label : labels) {
            // 라벨은 객체로도 문자열로도 올 수 있다
            String name = label.isTextual() ? label.asText() : label.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * {@code Link: <...>; rel="next", <...>; rel="last"} 에서 다음 페이지 존재 여부만 본다.
     *
     * <p>URL 을 파싱해 따라가지는 않는다. 커서를 진전시키는 것은 이 어댑터가 아니라
     * 수집 UseCase 의 일이다(이슈 #8).
     */
    private static boolean hasNextLink(String linkHeader) {
        return linkHeader != null && linkHeader.contains("rel=\"next\"");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            // 시각 하나를 못 읽었다고 수집 전체를 실패시키지 않는다. 커서 판단은 호출자가 한다
            log.warn("이슈 시각 파싱 실패 field={} 값을 비운다", field);
            return null;
        }
    }
}
