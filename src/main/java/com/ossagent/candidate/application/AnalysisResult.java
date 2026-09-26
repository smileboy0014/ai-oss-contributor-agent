package com.ossagent.candidate.application;

/**
 * 분석 배치 1회의 집계.
 *
 * <p>🔴 <b>이슈 본문·판정 내용을 담지 않는다.</b> 이 값은 로그로 나가고 #14 가 HTTP 응답에
 * 실을 것이다 — 수치와 우리 어휘만 남긴다 ({@code logging.md} · S-4).
 *
 * @param analyzed 후보가 되어 {@code ANALYZED} 로 남은 건수
 * @param rejected 분석은 됐으나 임계에 걸려 {@code REJECTED} 로 간 건수
 * @param failed   스키마 검증·호출 실패로 {@code FAILED} 가 된 건수
 * @param skipped  이미 후보가 있어 건너뛴 건수 — 재실행 멱등의 관측값이다
 * @param hasMore  상한에 걸려 남은 것이 있는가. {@code true} 면 다음 실행이 이어받는다
 */
public record AnalysisResult(int analyzed, int rejected, int failed, int skipped, boolean hasMore) {

    /** 분석을 시도한 건수 — 토큰이 나간 횟수와 같다. */
    public int attempted() {
        return analyzed + rejected + failed;
    }

    @Override
    public String toString() {
        return "AnalysisResult[analyzed=%d, rejected=%d, failed=%d, skipped=%d, hasMore=%s]"
                .formatted(analyzed, rejected, failed, skipped, hasMore);
    }
}
