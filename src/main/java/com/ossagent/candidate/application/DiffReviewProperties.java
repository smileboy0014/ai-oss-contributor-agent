package com.ossagent.candidate.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI diff 리뷰 설정 — {@code agent.review.*} · 이슈 #20.
 *
 * <h2>🔴 여기 임계를 넣지 않는다 — 이것은 비용 한도이지 판정 기준이 아니다</h2>
 *
 * <p>「몇 축이 위반이면 되돌리는가」는 <b>게이트 본체</b>이고, 이 파일에 들어오면
 * 배포 설정 한 줄로 느슨해질 수 있다 — S-2 의 「draft 플래그를 두면 언젠가 켜진다」와
 * 같은 모양이다. 그 숫자의 주인은 재시도 루프(#21)이고, <b>도메인 상수로 둘지
 * 설정값으로 둘지는 그쪽이 판단하고 근거를 남긴다.</b>
 *
 * <p>⚠️ 선례가 양쪽에 있어 한쪽으로 못 박지 않는다 — {@code ContributionCandidate
 * .MAX_ALLOWED_ATTEMPTS} 는 도메인 상수고(Q-6), {@code IssueAnalysisProperties
 * .minConfidence} 는 설정값인 판정 임계다.
 *
 * <p>⚠️ <b>{@code adapter/out/llm} 이 아니라 여기에 둔다.</b>
 * {@code ExternalAdapterIsolationTest} 가 {@code adapter.out.{github,llm,sandbox}} 패키지의
 * 빈이 대역 컨텍스트에 남아 있으면 잡는다 — {@code IssueAnalysisProperties} 가 #11 에서
 * 정확히 그 이유로 옮겨 온 선례다.
 *
 * @param maxDiffChars    🔴 <b>넘으면 자르지 않고 거부한다.</b> 잘린 diff 를 리뷰하면
 *                        「안 본 부분에 문제가 있었을 수 있다」가 되고 그것은 리뷰의 시늉이다 —
 *                        #7 이 규약 문서에서 내린 판단과 같다
 * @param maxOutputTokens 판정 4축 + 요약 + 지적이면 충분하다. {@code agent.llm} 의 16000 은 과하다
 */
@ConfigurationProperties("agent.review")
public record DiffReviewProperties(int maxDiffChars, int maxOutputTokens) {

    private static final int DEFAULT_MAX_DIFF_CHARS = 60_000;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4_000;

    public DiffReviewProperties {
        maxDiffChars = maxDiffChars <= 0 ? DEFAULT_MAX_DIFF_CHARS : maxDiffChars;
        maxOutputTokens = maxOutputTokens <= 0 ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens;
    }

    public static DiffReviewProperties defaults() {
        return new DiffReviewProperties(DEFAULT_MAX_DIFF_CHARS, DEFAULT_MAX_OUTPUT_TOKENS);
    }
}
