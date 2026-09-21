package com.ossagent.issue.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;
import java.time.Instant;

/**
 * open 이슈 조회 조건.
 *
 * <p>{@code updatedSince} 와 {@code etag} 를 <b>지금</b> 넣어 둔 것은 의도다.
 * 증분 수집(이슈 #8)은 「{@code updated_at} 커서 + ETag 조건부 요청」으로 가기로 정해져 있고
 * ({@code .claude/rules/context/external-deps.md}), 그때 이 시그니처를 다시 열면
 * 어댑터와 페이크를 함께 고쳐야 한다.
 *
 * <p><b>커서를 관리하지는 않는다.</b> 어디까지 읽었는지 기억하고 다음 커서를 정하는 것은
 * #8 의 UseCase 몫이다. 여기 있는 것은 「이번 한 번의 조회 조건」뿐이다.
 *
 * @param coordinates  대상 저장소
 * @param updatedSince 이 시각 이후 갱신된 것만. {@code null} 이면 전량
 * @param etag         직전 응답의 ETag. 변화가 없으면 304 로 돌아와 레이트리밋을 아끼고 본문도 받지 않는다
 * @param page         1 부터 시작하는 페이지 번호
 * @param perPage      페이지 크기. GitHub 상한은 100 이다
 */
public record IssueQuery(
        RepositoryCoordinates coordinates,
        Instant updatedSince,
        String etag,
        int page,
        int perPage) {

    /** GitHub 이 허용하는 페이지 크기 상한. 넘겨 보내면 조용히 100 으로 깎인다. */
    public static final int MAX_PER_PAGE = 100;

    public IssueQuery {
        if (coordinates == null) {
            throw new IllegalArgumentException("저장소 좌표가 없습니다");
        }
        if (page < 1) {
            throw new IllegalArgumentException("페이지는 1 이상이어야 합니다: " + page);
        }
        if (perPage < 1 || perPage > MAX_PER_PAGE) {
            throw new IllegalArgumentException(
                    "페이지 크기는 1~%d 여야 합니다: %d".formatted(MAX_PER_PAGE, perPage));
        }
        etag = etag == null || etag.isBlank() ? null : etag;
    }

    public static IssueQuery firstPage(RepositoryCoordinates coordinates) {
        return new IssueQuery(coordinates, null, null, 1, MAX_PER_PAGE);
    }

    public IssueQuery updatedSince(Instant since) {
        return new IssueQuery(coordinates, since, etag, page, perPage);
    }

    public IssueQuery withEtag(String value) {
        return new IssueQuery(coordinates, updatedSince, value, page, perPage);
    }

    public IssueQuery nextPage() {
        return new IssueQuery(coordinates, updatedSince, etag, page + 1, perPage);
    }

    public boolean isConditional() {
        return etag != null;
    }
}
