package com.ossagent.repository.application;

import com.ossagent.candidate.application.AnalysisResult;
import com.ossagent.issue.application.FilterResult;
import com.ossagent.issue.application.ScanResult;
import java.time.Instant;

/**
 * 파이프라인 1회의 집계 — 수집·필터·분석 세 단계를 합친다.
 *
 * <p>🔴 <b>이슈 본문·판정 내용을 담지 않는다.</b> 이 값은 로그로 나가고 진행 조회가
 * HTTP 로 내보낸다 — 수치와 우리 어휘만 (S-4 · {@code logging.md}).
 *
 * @param hasMore     🔴 세 단계 중 <b>하나라도</b> 남았으면 참. 「한 번 돌렸다」와
 *                    「다 돌았다」는 다르다 — 상한에 걸려 남는 것이 정상이다.
 *                    <b>자동으로 이어 달리지 않는다</b>(설계 ⑦) — 다음 주기가 이어받는다
 * @param delayedUntil 레이트리밋. 🔴 <b>실패가 아니라 지연</b>이다
 * @param skipReason  수집을 시작하지 않은 사유. 돌았으면 {@code null}
 */
public record ScanPipelineResult(
        int issuesSaved,
        int issuesJudged,
        int candidatesAnalyzed,
        int candidatesRejected,
        int candidatesFailed,
        int candidatesSkipped,
        boolean hasMore,
        Instant delayedUntil,
        ScanTarget.SkipReason skipReason) {

    /** 세 단계를 다 돈 경우. */
    public static ScanPipelineResult of(ScanResult scan, FilterResult filter,
            AnalysisResult analysis) {
        return new ScanPipelineResult(
                scan.savedCount(),
                filter.judged(),
                analysis.analyzed(),
                analysis.rejected(),
                analysis.failed(),
                analysis.skipped(),
                // 🔴 OR 로 모은다. 한 단계라도 남았으면 다음 주기가 할 일이 있다
                scan.hasMore() || filter.hasMore() || analysis.hasMore(),
                scan.delayedUntil(),
                null);
    }

    /** 수집 전에 끊었다 — 규약이 막았거나 읽지 못했다. */
    public static ScanPipelineResult skipped(ScanTarget.SkipReason reason) {
        return new ScanPipelineResult(0, 0, 0, 0, 0, 0, false, null, reason);
    }

    /**
     * 수집까지는 했으나 그 뒤가 막혔다 — <b>앞 단계 성과를 버리지 않는다</b> (NFR-4).
     *
     * <p>수집은 됐는데 분석이 죽었다고 수집분을 0으로 보고하면, 사람이 「아무 일도 없었다」로
     * 읽고 같은 구간을 다시 읽히게 된다.
     */
    public static ScanPipelineResult partial(ScanResult scan, FilterResult filter) {
        return new ScanPipelineResult(
                scan.savedCount(),
                filter == null ? 0 : filter.judged(),
                0, 0, 0, 0,
                true,
                scan.delayedUntil(),
                null);
    }

    public boolean isSkipped() {
        return skipReason != null;
    }

    /** 🔴 지연은 실패가 아니다 — {@code glossary.md} 「리밋을 실패라 쓰지 않는다」. */
    public boolean isDelayed() {
        return delayedUntil != null;
    }

    @Override
    public String toString() {
        if (isSkipped()) {
            return "ScanPipelineResult[skipped=%s]".formatted(skipReason);
        }
        return ("ScanPipelineResult[saved=%d, judged=%d, analyzed=%d, rejected=%d, "
                + "failed=%d, skipped=%d, hasMore=%s, delayedUntil=%s]")
                .formatted(issuesSaved, issuesJudged, candidatesAnalyzed, candidatesRejected,
                        candidatesFailed, candidatesSkipped, hasMore, delayedUntil);
    }
}
