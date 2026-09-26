package com.ossagent.issue.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 규칙 필터 설정 — #9.
 *
 * <p>⚠ <b>값을 바꿔도 기존 판정은 다시 서지 않는다</b>(알려진 한계). 재판정 트리거는
 * 「내용이 바뀌었는가」({@code filter_result IS NULL})이지 「규칙이 바뀌었는가」가 아니다.
 * 임계를 조정했으면 운영에서 대상을 골라 비운다 —
 * {@code UPDATE issue SET filter_result = NULL WHERE filter_judged_at < :규칙_변경_시각}.
 * 규칙 버전 해시로 자동화하는 것은 설정이 실제로 자주 바뀌면 그때 한다.
 *
 * @param minBodyLength    공백을 뺀 본문이 이 길이 미만이면 <b>보류</b>(배제 아님).
 *                         기본 200 — GitHub 이슈 URL 하나가 60~80자라 「링크만 붙인 이슈」를
 *                         잡으려면 그보다 커야 하고, 한두 문장짜리 정상 버그 리포트(100~150자)를
 *                         확정 배제하지 않으려면 그 위여야 한다. 확정 배제가 아니라 보류라
 *                         경계가 다소 어긋나도 손실이 작다. Phase 1 대상에서 실측 후 조정한다
 * @param maxCommentCount  코멘트가 이 수를 초과하면 <b>보류</b>. 기본 30 —
 *                         {@code spring-kafka} 에서 30 을 넘는 이슈는 대체로 설계 논의다
 * @param batchSize        한 트랜잭션에 판정할 이슈 수. {@code Issue.body} 가 TEXT 이고
 *                         저장소 하나에 이슈가 수천 건이라 전량을 한 트랜잭션에 올리면
 *                         메모리·커넥션 점유가 커진다
 * @param maxBatchesPerRun 1회 실행의 배치 상한. 판정은 진행을 보장하므로 무한루프가 날 수
 *                         없지만, <b>보장에 기대어 상한을 빼지 않는다</b> — 규칙을 잘못 고쳐
 *                         판정이 서지 않게 되면 상한이 유일한 제동이다
 */
@ConfigurationProperties("issue.filter")
public record IssueFilterProperties(
        Integer minBodyLength,
        Integer maxCommentCount,
        Integer batchSize,
        Integer maxBatchesPerRun) {

    /**
     * 🔴 본문 길이 하한의 상한이다. 말장난 같지만 이것이 없으면
     * <b>설정 한 줄로 {@code PASSED} 가 죽은 값이 된다</b> — 전건이 {@code SHORT_BODY} →
     * {@code UNDECIDED} 가 되고, 하류 #11 은 보류를 통과로 취급할 수밖에 없다.
     *
     * <p>5,000자는 「정상적인 이슈 본문이 이보다 길 이유가 없다」는 선이다. 넘기려면
     * <b>코드를 고쳐야 하고 그것이 리뷰에 보인다</b> — Q-6 의 재시도 절대 상한과 같은 장치다.
     */
    private static final int MAX_MIN_BODY_LENGTH = 5000;

    private static final int DEFAULT_MIN_BODY_LENGTH = 200;
    private static final int DEFAULT_MAX_COMMENT_COUNT = 30;
    private static final int DEFAULT_BATCH_SIZE = 200;
    private static final int DEFAULT_MAX_BATCHES = 100;

    public IssueFilterProperties {
        minBodyLength = minBodyLength == null ? DEFAULT_MIN_BODY_LENGTH : minBodyLength;
        maxCommentCount = maxCommentCount == null ? DEFAULT_MAX_COMMENT_COUNT : maxCommentCount;
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        maxBatchesPerRun = maxBatchesPerRun == null ? DEFAULT_MAX_BATCHES : maxBatchesPerRun;

        if (minBodyLength < 0) {
            throw new IllegalArgumentException("본문 길이 하한은 음수일 수 없습니다: " + minBodyLength);
        }
        if (minBodyLength > MAX_MIN_BODY_LENGTH) {
            throw new IllegalArgumentException(
                    "본문 길이 하한이 너무 큽니다 — 전건이 보류가 되어 PASSED 가 도달 불가능해집니다: "
                            + minBodyLength);
        }
        if (maxCommentCount < 1) {
            // 0 이면 코멘트가 하나라도 달린 이슈가 전부 보류다. 활발한 저장소에서는
            // 사실상 전건 보류이고, 위와 같은 이유로 PASSED 가 죽는다
            throw new IllegalArgumentException("코멘트 수 상한은 1 이상이어야 합니다: " + maxCommentCount);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("배치 크기는 1 이상이어야 합니다: " + batchSize);
        }
        if (maxBatchesPerRun < 1) {
            throw new IllegalArgumentException("배치 상한은 1 이상이어야 합니다: " + maxBatchesPerRun);
        }
    }
}
