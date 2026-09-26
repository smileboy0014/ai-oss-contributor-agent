package com.ossagent.candidate.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 구현 계획 설정 — {@code agent.plan.*} (이슈 #16).
 *
 * <p>위치가 {@code application} 인 이유는 {@code IssueAnalysisProperties} 와 같다 —
 * {@code adapter/out/*} 에 두면 {@code ExternalAdapterIsolationTest} 가 잡는다.
 *
 * @param maxAttempts      🔴 <b>3번째 재시도 축</b>이다 — 아래
 * @param maxOutputTokens  계획 응답의 출력 예산
 * @param maxPlannedFiles  계획이 지목할 수 있는 파일 수 상한 (FR-4)
 * @param maxPlannedLoc    변경 예상 라인 수 상한 (FR-4)
 */
@ConfigurationProperties("agent.plan")
public record ImplementationPlanProperties(
        int maxAttempts,
        int maxOutputTokens,
        int maxPlannedFiles,
        int maxPlannedLoc,
        Double scopeTolerance) {

    /**
     * 🔴 <b>기존 두 축과 섞지 않는다.</b>
     *
     * <table>
     *   <tr><th>축</th><th>키</th><th>무엇을 세나</th></tr>
     *   <tr><td>전송 계층</td><td>{@code agent.llm.max-retries}</td><td>429·5xx·타임아웃</td></tr>
     *   <tr><td>파이프라인</td><td>{@code agent.execution.max-retries}</td><td>{@code CODE}→{@code VERIFY}→{@code REVIEW} 한 바퀴</td></tr>
     *   <tr><td><b>계획 검증</b></td><td><b>여기</b></td><td>「모델이 <b>없는 파일을 지목</b>했다」</td></tr>
     * </table>
     *
     * <p>Q-6 의 「{@code ANALYZE}·{@code PLAN} 은 파이프라인 재시도 없음」은 <b>루프 카운터</b>
     * 이야기다. 전송 실패도 코드 실패도 아닌 <b>의미 실패</b>를 다루는 축이 없었다.
     *
     * <p>⚠️ <b>곱셈 예산</b> — 2 × (1 + {@code agent.llm.max-retries} 2) = <b>LLM 호출 최대 6회</b>.
     * 이 값을 올릴 때는 그 곱을 먼저 계산한다.
     *
     * <p>⚠️ 이름이 {@code max-attempts} 인 것은 의도다 — 값이 <b>총 시도 수</b>다.
     * {@code agent.execution.max-retries} 가 이름과 의미가 어긋나 혼란을 만든 자리라
     * (Q-6 의 경고), 새로 만드는 축은 처음부터 맞춰 둔다.
     */
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    /** 계획은 분석(1,500)보다 길고 코드(16,000)보다 짧다 */
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4_000;

    /**
     * 🔴 {@code agent.context.max-files}(12)보다 <b>작아야</b> 한다.
     * 본 것보다 많이 고치겠다는 계획은 근거가 없다.
     */
    private static final int DEFAULT_MAX_PLANNED_FILES = 8;

    /** PR 컨벤션의 「&lt; 400줄」과 같은 눈금 */
    private static final int DEFAULT_MAX_PLANNED_LOC = 400;

    /**
     * 이슈별 추정치에 곱하는 여유 — D-6.
     *
     * <p>🔴 <b>1.0 으로 조이지 않는다.</b> 추정은 추정이고, 분석이 「3파일」이라 했다고
     * 4파일 계획을 거부하면 정상 기여가 상한 소진으로 죽는다. 여유의 목적은
     * <b>scope creep</b>(3파일짜리 이슈에 12파일 계획)을 잡는 것이지 정밀도가 아니다.
     */
    private static final double DEFAULT_SCOPE_TOLERANCE = 2.0;

    public ImplementationPlanProperties {
        maxAttempts = maxAttempts <= 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        maxOutputTokens = maxOutputTokens <= 0 ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens;
        maxPlannedFiles = maxPlannedFiles <= 0 ? DEFAULT_MAX_PLANNED_FILES : maxPlannedFiles;
        maxPlannedLoc = maxPlannedLoc <= 0 ? DEFAULT_MAX_PLANNED_LOC : maxPlannedLoc;
        // ⚠ Double 로 받는다. double 이면 「설정 안 함」과 0.0 이 같아져 기본값을 둘 수 없다.
        //   1.0 미만이면 「추정보다 적게 고쳐야 한다」가 되어 정확한 추정일수록 거부된다
        scopeTolerance = scopeTolerance == null || scopeTolerance < 1.0
                ? DEFAULT_SCOPE_TOLERANCE
                : scopeTolerance;
    }

    public double scopeToleranceValue() {
        return scopeTolerance;
    }

    public static ImplementationPlanProperties defaults() {
        return new ImplementationPlanProperties(DEFAULT_MAX_ATTEMPTS, DEFAULT_MAX_OUTPUT_TOKENS,
                DEFAULT_MAX_PLANNED_FILES, DEFAULT_MAX_PLANNED_LOC, DEFAULT_SCOPE_TOLERANCE);
    }
}
