package com.ossagent.candidate.application;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이슈 분석 설정 — {@code agent.analysis.*}.
 *
 * <p>⚠️ <b>{@code adapter/out/llm} 이 아니라 여기에 둔다.</b> 값의 대부분(배치 크기·상한·
 * 신뢰도 임계)을 쓰는 것이 {@link AnalyzeIssuesUseCase} 이고, {@code issue} 도메인도
 * {@code IssueFilterProperties}·{@code IssueScanProperties} 를 {@code application} 에 둔다.
 *
 * <p>부수 효과가 하나 더 있다 — {@code ExternalAdapterIsolationTest} 는
 * {@code adapter.out.{github,llm,sandbox}} 패키지의 빈이 대역 컨텍스트에 남아 있으면 잡는다.
 * 설정 레코드는 대외 호출을 하지 않지만 그 패키지에 있으면 걸린다. 가드를 느슨하게 하는 대신
 * <b>위치를 맞췄다.</b>
 *
 * @param batchSize        한 번에 집어 오는 분석 대상 이슈 수
 * @param maxBatchesPerRun 한 실행의 상한. 이슈 수천 건에 무한 루프를 만들지 않는다
 * @param maxOutputTokens  관찰값 7개 + 요약이면 충분하다. {@code agent.llm} 의 16000 은 과하다
 * @param maxBodyChars     이슈 본문 절단 길이
 * @param minConfidence    🔴 미만이면 {@code REJECTED} — 아래
 */
@ConfigurationProperties("agent.analysis")
public record IssueAnalysisProperties(
        int batchSize,
        int maxBatchesPerRun,
        int maxOutputTokens,
        int maxBodyChars,
        BigDecimal minConfidence) {

    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int DEFAULT_MAX_BATCHES_PER_RUN = 20;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1_500;
    private static final int DEFAULT_MAX_BODY_CHARS = 20_000;

    /**
     * 🔴 <b>느슨한 쪽으로 잡는다.</b> 임계 미달은 {@code REJECTED} 이고 그것은 <b>종단</b>이다.
     * 나중에 값을 낮춰도 <b>이미 걸러진 후보는 돌아오지 않는다</b> — 재분석 경로가 없다.
     * 조이는 것은 언제든 되지만 푸는 것은 소급되지 않는다.
     *
     * <p>실측 데이터 0건에서 정한 값이라 추정이다. 설정으로 빼 둔 이유가 이것이다.
     */
    private static final BigDecimal DEFAULT_MIN_CONFIDENCE = new BigDecimal("0.50");

    public IssueAnalysisProperties {
        batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        maxBatchesPerRun = maxBatchesPerRun <= 0 ? DEFAULT_MAX_BATCHES_PER_RUN : maxBatchesPerRun;
        maxOutputTokens = maxOutputTokens <= 0 ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens;
        maxBodyChars = maxBodyChars <= 0 ? DEFAULT_MAX_BODY_CHARS : maxBodyChars;
        minConfidence = minConfidence == null ? DEFAULT_MIN_CONFIDENCE : minConfidence;
    }

    public static IssueAnalysisProperties defaults() {
        return new IssueAnalysisProperties(DEFAULT_BATCH_SIZE, DEFAULT_MAX_BATCHES_PER_RUN,
                DEFAULT_MAX_OUTPUT_TOKENS, DEFAULT_MAX_BODY_CHARS, DEFAULT_MIN_CONFIDENCE);
    }
}
