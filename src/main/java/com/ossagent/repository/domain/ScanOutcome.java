package com.ossagent.repository.domain;

import java.time.Instant;

/**
 * 스캔 실행 1회의 <b>결과 수치</b> — 엔티티가 저장하는 형태.
 *
 * <h2>🔴 {@code ScanPipelineResult} 와 같은 것을 담는데 왜 둘인가</h2>
 *
 * <p>{@code ScanPipelineResult}(application)는 세 도메인의 결과값
 * ({@code ScanResult}·{@code FilterResult}·{@code AnalysisResult})을 모아 만든다.
 * 그래서 그쪽은 <b>application 에 있어야 하고</b>, 엔티티가 그것을 받으면
 * domain 이 application 을 import 하게 된다 — 의존 방향이 뒤집힌다.
 *
 * <p>변환은 {@code JdbcScanExecutionRegistry} <b>한 곳에서만</b> 한다. 두 곳이 되면
 * 필드가 늘 때 한쪽이 빠지고, 빠진 쪽은 <b>0 으로 저장되어</b> 「돌았는데 아무 일도
 * 없었다」로 보인다.
 *
 * <p>🔴 <b>이슈 본문·판정 내용이 들어올 자리가 없다</b> (S-4). 수치와 우리 어휘뿐이다 —
 * 이 값은 DB 에 남고 진행 조회로 HTTP 에 나간다.
 *
 * @param hasMore      세 단계 중 <b>하나라도</b> 남았으면 참
 * @param delayedUntil 레이트리밋. 🔴 <b>실패가 아니라 지연</b>이다
 * @param skipReason   수집을 시작하지 않은 사유. 돌았으면 {@code null}
 */
public record ScanOutcome(
        int issuesSaved,
        int issuesJudged,
        int candidatesAnalyzed,
        int candidatesRejected,
        int candidatesFailed,
        int candidatesSkipped,
        boolean hasMore,
        Instant delayedUntil,
        ScanSkipReason skipReason) {

    /** 집계가 없는 실행 — 시작도 못 했거나 결과를 만들기 전에 죽었다. */
    public static ScanOutcome none() {
        return new ScanOutcome(0, 0, 0, 0, 0, 0, false, null, null);
    }
}
