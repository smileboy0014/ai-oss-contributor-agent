package com.ossagent.issue.application;

import com.ossagent.issue.domain.IssuePage;
import com.ossagent.issue.domain.IssueQuery;
import com.ossagent.issue.domain.IssueScanCursor;
import com.ossagent.issue.domain.IssueSnapshot;
import com.ossagent.issue.domain.IssueSource;
import com.ossagent.repository.application.IssueScanCursorUseCase;
import com.ossagent.repository.domain.RepositoryCoordinates;
import com.ossagent.support.github.GitHubRateLimitException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 대상 저장소의 open 이슈를 <b>증분으로</b> 수집한다 — #8.
 *
 * <h2>🔴 트랜잭션을 걸지 않는다</h2>
 *
 * <p>이 클래스에 {@code @Transactional} 이 <b>없는 것은 의도</b>다. GitHub 호출이 이 안에서
 * 일어나는데, 트랜잭션으로 감싸면 응답을 기다리는 동안 DB 커넥션이 잡힌다
 * ({@code architecture.md} §2). 저장은 {@link IssuePageWriter} 가 페이지마다 <b>짧게</b> 연다.
 *
 * <h2>🔴 레이트리밋은 실패가 아니라 지연이다</h2>
 *
 * <p>{@code Thread.sleep} 으로 기다리지 <b>않는다.</b> 1차 리밋 리셋은 최대 1시간이고,
 * 단일 프로세스(Q-3)에서 스레드를 그만큼 잡으면 코딩 작업(최대 30분)과 겹쳐 죽는다.
 * 대신 <b>정상 종료하고 커서를 남겨</b> 다음 스캔이 이어받게 한다.
 *
 * <p>부분 수집은 <b>버리지 않는다.</b> 3페이지를 읽고 걸렸으면 그 3페이지를 저장하고
 * 커서를 거기까지 전진시킨다 — 버리면 같은 구간을 다시 읽어 리밋을 또 태운다.
 *
 * <h2>🔴 커서 전진이 안전한 이유는 오름차순 정렬이다</h2>
 *
 * <p>{@link IssueSource} 가 {@code updated_at} <b>오름차순</b>을 계약으로 보장한다.
 * 그래서 앞 N 페이지가 가장 오래된 것들이고, 그 최대값으로 전진해도 안 읽은 구간이 뒤에 남는다.
 * 내림차순으로 바뀌면 <b>안 읽은 오래된 이슈를 영구히 건너뛴다.</b>
 *
 * <h2>S-6 — 후보를 만들지 않는다</h2>
 *
 * <p>이 UseCase 는 스케줄러가 주기적으로 도는 자동 경로의 첫 단계다. 여기서
 * {@code ContributionCandidate} 를 만들면 「사람이 고른다」가 무너진다.
 * <b>{@code Issue} 행만 만든다</b> — {@code candidate} 도메인을 import 하지 않는 것이 그 준수다.
 */
@Service
public class ScanIssuesUseCase {

    private static final Logger log = LoggerFactory.getLogger(ScanIssuesUseCase.class);

    private final IssueSource issueSource;
    private final IssuePageWriter pageWriter;
    private final IssueScanCursorUseCase cursors;
    private final IssueScanProperties properties;
    private final Clock clock;

    public ScanIssuesUseCase(IssueSource issueSource, IssuePageWriter pageWriter,
            IssueScanCursorUseCase cursors, IssueScanProperties properties, Clock clock) {
        this.issueSource = issueSource;
        this.pageWriter = pageWriter;
        this.cursors = cursors;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 저장소 하나를 스캔한다.
     *
     * @throws com.ossagent.support.github.GitHubApiException 권한 오류 등 <b>지연이 아닌</b> 실패
     */
    public ScanResult scan(Long repositoryId, RepositoryCoordinates coordinates) {
        IssueScanCursor cursor = loadCursor(repositoryId);
        IssueQuery query = cursor.firstPageQuery(coordinates);

        int savedCount = 0;
        int pagesRead = 0;
        Instant watermark = null;
        String firstPageEtag = null;

        while (pagesRead < properties.maxPagesPerScan()) {
            IssuePage page;
            try {
                page = issueSource.fetchOpenIssues(query);
            } catch (GitHubRateLimitException e) {
                return delay(repositoryId, coordinates, cursor, watermark, savedCount, pagesRead, e);
            }
            pagesRead++;

            if (page.unchanged()) {
                // 304 — 커서도 ETag 도 그대로 둔다. 짝이 유지된다
                log.info("이슈 스캔 변경 없음 repo={} cursor={}", coordinates.fullName(), cursor.updatedSince());
                return ScanResult.notModified();
            }
            if (pagesRead == 1) {
                firstPageEtag = page.etag();   // ETag 는 page 1 의 것만 커서에 싣는다
            }

            List<IssueSnapshot> snapshots = page.issuesOnly();   // PR 을 이슈로 저장하지 않는다
            Instant pageWatermark =
                    pageWriter.save(repositoryId, coordinates.owner(), coordinates.name(), snapshots);
            savedCount += snapshots.size();
            watermark = later(watermark, pageWatermark);

            if (!page.hasNext()) {
                commit(repositoryId, cursor, watermark, firstPageEtag);
                log.info("이슈 스캔 완료 repo={} saved={} pages={}",
                        coordinates.fullName(), savedCount, pagesRead);
                return ScanResult.completed(savedCount, pagesRead, false);
            }
            query = query.nextPage();   // ETag 를 승계하지 않는다 — page 와도 짝이다
        }

        // 페이지 상한에 걸렸다. 잘렸다는 사실을 남긴다 — 「다 읽었다」와 구분해야 한다
        commit(repositoryId, cursor, watermark, firstPageEtag);
        log.warn("이슈 스캔 페이지 상한 도달 repo={} saved={} pages={} limit={} — 다음 스캔이 이어받는다",
                coordinates.fullName(), savedCount, pagesRead, properties.maxPagesPerScan());
        return ScanResult.completed(savedCount, pagesRead, true);
    }

    /**
     * 리밋에 걸렸다 — <b>지금까지 저장한 것을 지키고</b> 정상 종료한다.
     *
     * <p>예외를 다시 던지지 않는 것이 이 이슈의 핵심이다. 「리밋 소진은 정상 운영 상황」이고,
     * 실패로 처리하면 후보가 코드 문제 없이 죽는다.
     */
    private ScanResult delay(Long repositoryId, RepositoryCoordinates coordinates,
            IssueScanCursor cursor, Instant watermark, int savedCount, int pagesRead,
            GitHubRateLimitException e) {
        commit(repositoryId, cursor, watermark, null);

        Instant now = clock.instant();
        Instant resumeAt = e.earliestRetryAt(now);
        if (resumeAt == null) {
            // Retry-After 도 resetAt 도 없다 — 보수적인 기본값을 쓴다.
            // 우리가 추측해 짧게 잡으면 2차 리밋에서는 차단이 더 길어진다
            resumeAt = now.plus(properties.secondaryLimitBackoff());
        }
        log.warn("이슈 스캔 레이트리밋 지연 repo={} scope={} saved={} pages={} resumeAt={}",
                coordinates.fullName(), e.scope(), savedCount, pagesRead, resumeAt);
        return ScanResult.delayed(savedCount, pagesRead, resumeAt);
    }

    /**
     * 커서를 전진시킨다. 🔴 <b>저장이 끝난 뒤에만</b> 부른다 — 조회 직후에 전진시키면
     * 저장 실패 시 그 구간을 영영 다시 읽지 않는다.
     */
    private void commit(Long repositoryId, IssueScanCursor cursor, Instant watermark, String etag) {
        IssueScanCursor advanced = cursor.advancedTo(watermark);
        if (advanced.updatedSince() != null && advanced.updatedSince().equals(cursor.updatedSince())) {
            // 커서가 그대로다 — page 1 ETag 를 붙여 다음 스캔이 조건부로 물어보게 한다
            advanced = advanced.withEtag(etag);
        }
        cursors.updateCursor(repositoryId, advanced.updatedSince(), advanced.etag());
    }

    private IssueScanCursor loadCursor(Long repositoryId) {
        return IssueScanCursor.of(
                cursors.cursorUpdatedAt(repositoryId), cursors.cursorEtag(repositoryId));
    }

    private static Instant later(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
}
