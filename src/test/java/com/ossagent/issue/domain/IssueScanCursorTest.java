package com.ossagent.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.repository.domain.RepositoryCoordinates;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 커서와 ETag 의 <b>짝</b>을 고정한다 — #8 §3.2.
 *
 * <p>여기가 틀리면 대개 조용하다 — 서버가 200 을 돌려줄 뿐이라 동작은 맞고 조건부 요청의
 * 이득만 사라진다. 그러나 <b>우연히 매치되어 304 를 받으면 그 구간을 통째로 건너뛴다.</b>
 * 조용한 데이터 손실이라 테스트로 고정한다.
 */
class IssueScanCursorTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void 커서가_전진하면_ETag_를_버린다() {
        IssueScanCursor cursor = IssueScanCursor.of(T0, "etag-for-T0");

        IssueScanCursor advanced = cursor.advancedTo(T1);

        assertThat(advanced.updatedSince()).isEqualTo(T1);
        assertThat(advanced.etag())
                .as("""
                        ETag 는 URL 단위로 유효한데 since 가 URL 에 들어간다.
                        since 가 T0→T1 로 바뀌면 T0 용 ETag 는 다른 리소스의 것이다.
                        들고 가면 우연히 매치될 때 페이지를 통째로 건너뛴다.""")
                .isNull();
    }

    @Test
    void 변경이_없으면_커서와_ETag_를_둘_다_유지한다() {
        IssueScanCursor cursor = IssueScanCursor.of(T0, "etag-for-T0");

        IssueScanCursor same = cursor.unchanged();

        assertThat(same.updatedSince()).isEqualTo(T0);
        assertThat(same.etag())
                .as("304 는 「그 URL 의 내용이 그대로」라는 뜻이다 — ETag 도 유효하다")
                .isEqualTo("etag-for-T0");
    }

    @Test
    void 커서는_뒤로_가지_않는다() {
        IssueScanCursor cursor = IssueScanCursor.of(T1, null);

        assertThat(cursor.advancedTo(T0).updatedSince())
                .as("페이지네이션 중 순서가 변동해 더 이른 값이 들어와도 후퇴하면 이미 본 구간을 다시 읽는다")
                .isEqualTo(T1);
    }

    @Test
    void 같은_값으로는_전진하지_않아_ETag_가_살아남는다() {
        IssueScanCursor cursor = IssueScanCursor.of(T0, "etag-for-T0");

        // 경계가 포함(inclusive)이라 경계 이슈가 재수집되면 워터마크가 그대로일 수 있다.
        // 그때는 URL 이 안 바뀌었으므로 ETag 가 여전히 유효하다
        assertThat(cursor.advancedTo(T0).etag()).isEqualTo("etag-for-T0");
    }

    @Test
    void 첫_스캔은_since_없이_조회한다() {
        IssueQuery query = IssueScanCursor.NONE.firstPageQuery(new RepositoryCoordinates("a", "b"));

        assertThat(IssueScanCursor.NONE.isFirstScan()).isTrue();
        assertThat(query.updatedSince()).isNull();
        assertThat(query.isConditional()).isFalse();
        assertThat(query.page()).isEqualTo(1);
    }

    @Test
    void 다음_페이지는_ETag_를_승계하지_않는다() {
        // ETag 의 짝은 since 만이 아니라 page 도 있다.
        // 1페이지 응답의 ETag 를 2페이지 요청에 붙이면 다른 리소스를 비교하게 된다
        IssueQuery first = IssueScanCursor.of(T0, "etag-page-1")
                .firstPageQuery(new RepositoryCoordinates("a", "b"));

        assertThat(first.etag()).isEqualTo("etag-page-1");
        assertThat(first.nextPage().etag())
                .as("page 가 URL 에 들어가므로 1페이지 ETag 는 2페이지에 쓸 수 없다")
                .isNull();
        assertThat(first.nextPage().updatedSince())
                .as("since 는 같은 스캔 내내 유지된다")
                .isEqualTo(T0);
    }
}
