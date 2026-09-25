package com.ossagent.issue.domain;

import com.ossagent.repository.domain.RepositoryCoordinates;
import java.time.Instant;

/**
 * 이슈 증분 수집의 <b>커서</b> — 「데이터를 어디까지 봤나」 — #8.
 *
 * <p>{@code updatedSince} 와 {@code etag} 를 <b>따로 다루지 않는다.</b> 둘은 짝이고,
 * 짝이 어긋나면 조용히 틀리기 때문에 한 타입으로 묶어 함께만 움직이게 한다.
 *
 * <h2>🔴 ETag 는 {@code since} 와 짝이다</h2>
 *
 * <p>ETag 는 <b>URL 단위</b>로 유효한데 {@code since} 가 URL 에 들어간다.
 *
 * <pre>
 * 1회차: GET /issues?since=T0  → 200, ETag=E0, 최대 updatedAt = T1
 * 2회차: GET /issues?since=T1  + If-None-Match: E0   ← ❌ E0 는 T0 용이다
 * </pre>
 *
 * <p>그래서 {@link #advancedTo(Instant)} 는 ETag 를 <b>버린다.</b>
 * 변한 게 없어 304 를 받았을 때만({@link #unchanged()}) 둘 다 유지한다.
 *
 * <p>⚠ 틀려도 대개는 조용하다 — 매치되지 않아 200 을 받고 성능만 손해다. 그러나
 * <b>우연히 매치되어 304 를 받으면 그 구간을 통째로 건너뛴다.</b> 조용한 데이터 손실이다.
 *
 * <h2>경계는 포함(inclusive)이다</h2>
 *
 * <p>GitHub 의 {@code updated_at} 은 <b>초 단위</b>라 같은 초에 갱신된 이슈가 여럿일 수 있다.
 * 다음 {@code since} 를 {@code max + 1s}(배타)로 잡으면 <b>경계 초의 미수집 이슈가 영구 누락</b>된다.
 *
 * <p>{@code since = max}(포함)로 잡는다. 경계 이슈가 매번 1건 재수집되지만
 * 멱등 upsert 가 흡수한다. 페이지네이션 도중 순서가 변동해 한 건이 미끄러져도
 * 다음 스캔이 경계부터 다시 읽어 회수한다.
 */
public record IssueScanCursor(Instant updatedSince, String etag) {

    /** 아직 한 번도 수집하지 않은 상태. */
    public static final IssueScanCursor NONE = new IssueScanCursor(null, null);

    public IssueScanCursor {
        etag = etag == null || etag.isBlank() ? null : etag;
    }

    public static IssueScanCursor of(Instant updatedSince, String etag) {
        return new IssueScanCursor(updatedSince, etag);
    }

    /**
     * 커서를 {@code newWatermark} 까지 전진시킨다. <b>ETag 는 버린다</b> — 위 참조.
     *
     * <p>뒤로 가지 않는다. 페이지네이션 도중 순서가 변동해 더 이른 값이 들어와도
     * 커서가 후퇴하면 이미 본 구간을 다시 읽는다.
     */
    public IssueScanCursor advancedTo(Instant newWatermark) {
        if (newWatermark == null) {
            return this;
        }
        if (updatedSince != null && !newWatermark.isAfter(updatedSince)) {
            return this;   // 후퇴·정체 — 그대로 둔다
        }
        return new IssueScanCursor(newWatermark, null);
    }

    /** 304 를 받았다 — 변한 게 없으므로 커서와 ETag 를 <b>둘 다</b> 유지한다. */
    public IssueScanCursor unchanged() {
        return this;
    }

    /** page 1 응답의 ETag 를 기록한다. 커서가 그대로일 때만 의미가 있다. */
    public IssueScanCursor withEtag(String newEtag) {
        return new IssueScanCursor(updatedSince, newEtag);
    }

    public boolean isFirstScan() {
        return updatedSince == null;
    }

    /** 이 커서로 1페이지를 조회하는 질의. */
    public IssueQuery firstPageQuery(RepositoryCoordinates coordinates) {
        return new IssueQuery(coordinates, updatedSince, etag, 1, IssueQuery.MAX_PER_PAGE);
    }
}
