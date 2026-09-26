package com.ossagent.repository.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 저장소 분석 설정 — {@code agent.context.*} (이슈 #15).
 *
 * <p>값이 전부 <b>실측 0건의 추정</b>이다. 그래서 코드 상수가 아니라 설정으로 뺐다 —
 * 첫 End-to-End 를 돌려 보고 조정하는 것이 순서다.
 *
 * <p>⚠️ 접두어가 {@code repository.*} 가 아니라 {@code agent.context.*} 인 것은
 * <b>소비자 기준</b>이다. 이 값들이 정하는 것은 저장소를 어떻게 관리하느냐가 아니라
 * <b>LLM 에 얼마나 보내느냐</b>이고, 그 예산은 {@code agent.analysis.max-body-chars} ·
 * {@code agent.llm.max-output-tokens} 와 같은 자리에서 읽혀야 한다.
 *
 * <p>위치가 {@code application} 인 이유는 {@code IssueAnalysisProperties} 와 같다 —
 * {@code adapter/out/*} 에 두면 {@code ExternalAdapterIsolationTest} 가 잡는다.
 *
 * @param maxFiles        컨텍스트에 담을 파일 수 상한
 * @param maxFetchAttempts 🔴 <b>읽기를 시도하는 횟수</b>의 상한 — {@code maxFiles} 와 다른 축이다.
 *                         아래 참조
 * @param maxTotalChars   전체 문자 수 상한
 * @param maxFileChars    파일 하나의 상한. 넘으면 <b>통째로 버린다</b> — 잘린 소스는 모델을 헷갈리게 한다
 * @param maxKeywords     키워드 수 상한. 너무 많으면 점수가 평평해져 변별력이 사라진다
 * @param importExpansion 1-hop 의존성 확장을 켜는가 (FR-7)
 */
@ConfigurationProperties("agent.context")
public record RepositoryContextProperties(
        int maxFiles,
        int maxFetchAttempts,
        int maxTotalChars,
        int maxFileChars,
        int maxKeywords,
        Boolean importExpansion) {

    private static final int DEFAULT_MAX_FILES = 12;

    /**
     * 🔴 <b>{@code maxFiles} 만으로는 호출 수가 묶이지 않는다.</b>
     *
     * <p>파일 예산은 <b>성공한 선별</b>만 센다. 읽기에 실패한 경로(404 · 1MB 초과 ·
     * 심볼릭링크)는 예산을 쓰지 않으므로, 실패만 계속되면 <b>후보 전량을 순회하며
     * 계속 호출</b>한다. 흔한 낱말 하나가 수천 경로에 걸릴 수 있고(`TERM` 은 경로
     * 부분 문자열 매칭이다), 그러면 저장소 하나가 시간당 예산을 태워
     * <b>같은 토큰을 쓰는 규약 수집(#7)·이슈 수집(#8)까지 막는다.</b>
     *
     * <p>그래서 「몇 개를 담을 것인가」와 「몇 번 두드릴 것인가」를 가른다.
     * 기본값은 파일 상한의 두 배 — 실패가 절반이어도 목표를 채울 수 있고,
     * 전부 실패해도 호출이 24회에서 멈춘다.
     */
    private static final int DEFAULT_MAX_FETCH_ATTEMPTS = 24;
    private static final int DEFAULT_MAX_TOTAL_CHARS = 120_000;
    private static final int DEFAULT_MAX_FILE_CHARS = 40_000;
    private static final int DEFAULT_MAX_KEYWORDS = 40;

    public RepositoryContextProperties {
        maxFiles = maxFiles <= 0 ? DEFAULT_MAX_FILES : maxFiles;
        maxFetchAttempts = maxFetchAttempts <= 0 ? DEFAULT_MAX_FETCH_ATTEMPTS : maxFetchAttempts;
        // 시도 상한이 파일 상한보다 작으면 목표를 채울 수 없다. 설정 실수를 조용히
        // 받아들이지 않고 올려 잡는다 — 「담을 수 있는데 두드릴 수 없는」 상태를 만들지 않는다
        maxFetchAttempts = Math.max(maxFetchAttempts, maxFiles);
        maxTotalChars = maxTotalChars <= 0 ? DEFAULT_MAX_TOTAL_CHARS : maxTotalChars;
        maxFileChars = maxFileChars <= 0 ? DEFAULT_MAX_FILE_CHARS : maxFileChars;
        maxKeywords = maxKeywords <= 0 ? DEFAULT_MAX_KEYWORDS : maxKeywords;
        // ⚠ Boolean 으로 받는다. boolean 이면 「설정하지 않음」과 「false」가 같아져
        //    기본값을 true 로 둘 수 없다
        importExpansion = importExpansion == null || importExpansion;
    }

    public static RepositoryContextProperties defaults() {
        return new RepositoryContextProperties(DEFAULT_MAX_FILES, DEFAULT_MAX_FETCH_ATTEMPTS,
                DEFAULT_MAX_TOTAL_CHARS, DEFAULT_MAX_FILE_CHARS, DEFAULT_MAX_KEYWORDS, true);
    }

    public boolean importExpansionEnabled() {
        return Boolean.TRUE.equals(importExpansion);
    }
}
