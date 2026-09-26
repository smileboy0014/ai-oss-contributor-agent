package com.ossagent.issue.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 요구가 불명확한 이슈를 가른다 — #9 규칙 ③ · 완료조건 3.
 *
 * <h2>🔴 「규칙으로 확정되는 것」과 「LLM 이 봐야 하는 것」을 가른다</h2>
 *
 * <p>완료조건 3이 요구하는 것이 정확히 이 구분이다. 셋을 같은 배제로 뭉개면
 * 명확한 이슈를 대량으로 잃는다.
 *
 * <table border="1">
 *   <caption>신호별 판정</caption>
 *   <tr><th>신호</th><th>판정</th><th>왜</th></tr>
 *   <tr><td>본문이 없다</td><td>{@code REJECTED}</td><td>판정할 것이 없다. 확정이다</td></tr>
 *   <tr><td>본문이 짧다</td><td>{@code UNDECIDED}</td>
 *       <td>짧다고 불명확한 것이 아니다 — 스택트레이스 링크 한 줄짜리 명확한 버그 리포트가 있다</td></tr>
 *   <tr><td>코멘트가 많다</td><td>{@code UNDECIDED}</td>
 *       <td>상관이지 인과가 아니다 — 활발한 논의일 수도 있다</td></tr>
 * </table>
 *
 * <p>⚠ 본문 길이와 코멘트 수에 <b>같은 잣대</b>를 댄다. 한쪽만 「상관일 뿐」이라며
 * 보류로 두고 다른 쪽을 확정 배제로 두는 것은 근거가 없다.
 *
 * <p>⚠ 두 신호가 동시에 걸려도 사유는 <b>둘 다</b> 남는다. 어느 쪽이 얼마나 자주
 * 걸리는지를 봐야 임계를 고칠 수 있다 (FR-2).
 */
public final class UnclearRequirementRule implements FilterRule {

    private final int minBodyLength;
    private final int maxCommentCount;

    /**
     * @param minBodyLength   공백을 제거한 본문이 이 길이 미만이면 {@code UNDECIDED}.
     *                        확정 배제가 아니라 보류라 경계가 다소 어긋나도 손실이 작다
     * @param maxCommentCount 코멘트가 이 수를 <b>초과</b>하면 {@code UNDECIDED}
     */
    public UnclearRequirementRule(int minBodyLength, int maxCommentCount) {
        if (minBodyLength < 0) {
            throw new IllegalArgumentException("본문 길이 하한은 음수일 수 없다: " + minBodyLength);
        }
        if (maxCommentCount < 0) {
            throw new IllegalArgumentException("코멘트 수 상한은 음수일 수 없다: " + maxCommentCount);
        }
        this.minBodyLength = minBodyLength;
        this.maxCommentCount = maxCommentCount;
    }

    @Override
    public List<FilterReason> evaluate(Issue issue) {
        List<FilterReason> reasons = new ArrayList<>(2);

        int length = meaningfulLength(issue.getBody());
        if (length == 0) {
            reasons.add(FilterReason.EMPTY_BODY);
        } else if (length < minBodyLength) {
            reasons.add(FilterReason.SHORT_BODY);
        }

        Integer comments = issue.getCommentCount();
        if (comments != null && comments > maxCommentCount) {
            reasons.add(FilterReason.HEAVY_DISCUSSION);
        }

        return List.copyOf(reasons);
    }

    /**
     * 공백을 뺀 길이.
     *
     * <p>공백만 있는 본문을 「있다」로 세면 빈 본문 배제가 개행 하나로 우회된다.
     * 마크다운 이슈 템플릿은 빈 줄이 많아 실제로 흔하다.
     */
    private static int meaningfulLength(String body) {
        if (body == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < body.length(); i++) {
            if (!Character.isWhitespace(body.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
