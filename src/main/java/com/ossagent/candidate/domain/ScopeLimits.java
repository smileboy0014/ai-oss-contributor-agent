package com.ossagent.candidate.domain;

/**
 * 계획의 범위를 재는 <b>두 눈금</b> — 이슈 #16 FR-4.
 *
 * <h2>🔴 하나로는 「범위가 이슈를 넘지 않는가」를 못 잰다</h2>
 * 고정 상한만 두면 <b>파일 7개짜리 무관한 계획이 그대로 통과</b>한다. 이슈 본문이 물은 것은
 * 「범위가 <b>이슈를</b> 넘지 않는가」이고, 그 눈금은 이슈마다 다르다.
 *
 * <table>
 *   <tr><th>눈금</th><th>출처</th><th>막는 것</th></tr>
 *   <tr><td>고정 상한</td><td>{@code agent.plan.max-planned-*}</td><td>파이프라인 폭주 — 절대 천장</td></tr>
 *   <tr><td><b>이슈별 허용</b></td><td>분석 추정치 × {@code scope-tolerance}</td><td><b>scope creep</b></td></tr>
 * </table>
 *
 * <p>이슈별 눈금은 새로 만드는 것이 아니다 — {@code IssueAnalysis.estimatedFiles}·
 * {@code estimatedLoc} 가 <b>이미 후보에 영속돼</b> 있다(#11).
 *
 * @param maxFiles           절대 천장 — 파일 수
 * @param maxLoc             절대 천장 — 라인 수
 * @param issueEstimatedFiles 분석이 추정한 파일 수. {@code null}·{@code 0} 이면 <b>이슈별 검사를 건너뛴다</b>
 * @param issueEstimatedLoc  분석이 추정한 라인 수. 〃
 * @param tolerance          추정치에 곱하는 여유. 추정은 추정이므로 1.0 으로 조이지 않는다
 */
public record ScopeLimits(
        int maxFiles,
        int maxLoc,
        Integer issueEstimatedFiles,
        Integer issueEstimatedLoc,
        double tolerance) {

    public ScopeLimits {
        if (maxFiles < 1 || maxLoc < 1) {
            throw new IllegalArgumentException(
                    "범위 상한은 1 이상이어야 한다: maxFiles=%d maxLoc=%d".formatted(maxFiles, maxLoc));
        }
        if (tolerance < 1.0) {
            // 1.0 미만이면 「추정보다 적게 고쳐야 한다」가 되어, 추정이 정확할수록 거부된다
            throw new IllegalArgumentException("scope-tolerance 는 1.0 이상이어야 한다: " + tolerance);
        }
    }

    /**
     * 이슈별 파일 허용치. 추정을 모르면 {@code 0} — <b>호출자가 「검사하지 않음」으로 읽는다.</b>
     *
     * <p>🔴 모르는 것을 <b>최강 제약</b>으로 번역하지 않는다. 추정치가 없다고 「0개만 고쳐야
     * 한다」로 읽으면 모든 계획이 거부된다 — Q-6 이 적어 둔 {@code maxAttempts = 0} 함정과
     * 같은 계열이다.
     */
    public int allowedFiles() {
        return scaled(issueEstimatedFiles);
    }

    /** 이슈별 라인 허용치. 추정을 모르면 {@code 0} (= 검사하지 않음) */
    public int allowedLoc() {
        return scaled(issueEstimatedLoc);
    }

    public boolean checksIssueFiles() {
        return allowedFiles() > 0;
    }

    public boolean checksIssueLoc() {
        return allowedLoc() > 0;
    }

    private int scaled(Integer estimate) {
        if (estimate == null || estimate <= 0) {
            return 0;
        }
        return (int) Math.ceil(estimate * tolerance);
    }
}
