package com.ossagent.issue.application;

import java.time.Instant;

/**
 * 스캔 한 번의 결과 — #8.
 *
 * <p>🔴 <b>레이트리밋은 실패가 아니라 지연</b>이므로 예외가 아니라 이 값으로 나온다.
 * 「언제 다시 부를지」는 <b>호출자(#14 스케줄러)가 정한다</b> — 이 UseCase 는 사실만 전한다.
 *
 * @param savedCount   이번에 저장(신규+갱신)한 이슈 수
 * @param pagesRead    읽은 페이지 수
 * @param unchanged    304 를 받아 아무것도 읽지 않았다
 * @param hasMore      🔴 <b>페이지 상한에 걸려 잘렸다.</b> 「다 읽었다」와 구분해야 한다 —
 *                     구분이 없으면 첫 스캔이 수백 건에서 잘렸는데 아무 데도 안 남는다
 * @param delayedUntil 리밋에 걸려 중단했고 이 시각 이후에 재개해야 한다. 정상 완료면 {@code null}
 */
public record ScanResult(
        int savedCount,
        int pagesRead,
        boolean unchanged,
        boolean hasMore,
        Instant delayedUntil) {

    public static ScanResult completed(int savedCount, int pagesRead, boolean hasMore) {
        return new ScanResult(savedCount, pagesRead, false, hasMore, null);
    }

    /**
     * 304 — 마지막 수집 이후 바뀐 것이 없다.
     *
     * <p>이름이 {@code unchanged} 가 아닌 이유는 record 컴포넌트 접근자와 겹치기 때문이다.
     */
    public static ScanResult notModified() {
        return new ScanResult(0, 1, true, false, null);
    }

    /**
     * 리밋에 걸려 중단했다. <b>지금까지 저장한 것은 유효하다</b> — 부분 수집을 버리지 않는다.
     */
    public static ScanResult delayed(int savedCount, int pagesRead, Instant until) {
        return new ScanResult(savedCount, pagesRead, false, true, until);
    }

    public boolean isDelayed() {
        return delayedUntil != null;
    }
}
