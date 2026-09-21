package com.ossagent.issue.domain;

import java.util.List;

/**
 * 이슈 조회 한 페이지의 결과.
 *
 * <p><b>레이트리밋 정보를 담지 않는다.</b> 남은 호출 수는 GitHub 의 개념이고 도메인에 뜻이 없다.
 * 반면 {@code etag}·{@code unchanged} 는 <b>다음 수집에 되돌려 줄 커서</b>라 도메인이 들고 있어야 한다
 * — 저장 주체가 {@code issue} 도메인이기 때문이다(이슈 #8). 둘의 차이는 「누가 그 값을 기억해야 하는가」다.
 *
 * @param issues    이 페이지의 항목. <b>PR 이 섞여 있을 수 있다</b> — {@link IssueSnapshot#isIssue()}
 * @param etag      이 응답의 ETag. 다음 조회에 {@link IssueQuery#withEtag(String)} 로 되돌려 준다
 * @param unchanged 304 — 직전 조회 이후 바뀐 것이 없다. {@code issues} 는 비어 있고, 「이슈가 없다」와 다르다
 * @param hasNext   다음 페이지가 있는가
 */
public record IssuePage(List<IssueSnapshot> issues, String etag, boolean unchanged, boolean hasNext) {

    public IssuePage {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static IssuePage unchanged(String etag) {
        return new IssuePage(List.of(), etag, true, false);
    }

    /** PR 을 걸러낸 진짜 이슈만. 수집·필터 단계가 쓰는 기본 진입점이다. */
    public List<IssueSnapshot> issuesOnly() {
        return issues.stream().filter(IssueSnapshot::isIssue).toList();
    }
}
