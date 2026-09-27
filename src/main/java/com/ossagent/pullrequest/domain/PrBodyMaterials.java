package com.ossagent.pullrequest.domain;

import com.ossagent.support.ExternalText;

/**
 * {@link PrBody} 의 재료. <b>전부 선택적</b>이고, 없으면 그 절이 통째로 빠진다.
 *
 * <h2>🔴 여기는 스크럽 「전」이다 — 그것이 이 타입의 정체다</h2>
 *
 * <p>세 필드가 {@link ExternalText} 다. 상류({@code GeneratedChange.testResult}·
 * {@code reviewResult})에 강제 지점이 아직 없어 <b>원문이 그대로 담긴다.</b>
 * 유일한 소비자가 {@link PrBody#compose} 이고 그것이 {@link PrBody} 생성자를 타므로
 * <b>여기서 나가는 길이 스크럽을 통과하는 길 하나뿐</b>이다.
 *
 * <p>⚠️ 이 타입에 게터 말고 <b>다른 출구를 만들지 않는다.</b> {@code toString} 으로 본문이
 * 새면 스크럽 지점을 우회한 것이 된다 — 그래서 아래에서 길이만 노출한다.
 *
 * @param template            대상 저장소의 PR 템플릿 원문. 없으면 {@code null}
 * @param changeSummary       무엇을 왜 바꿨나. <b>우리 어휘</b>라 외부 텍스트가 아니다
 * @param issueReference      이슈 참조 줄. 🔴 규약이 요구하면 <b>비어 있지 않다</b> — S-5
 * @param verificationSummary 샌드박스 검증 결과 요약
 * @param reviewSummary       AI diff 리뷰 결과 요약
 */
public record PrBodyMaterials(
        @ExternalText(ExternalText.Source.TARGET_REPOSITORY) String template,
        String changeSummary,
        String issueReference,
        @ExternalText(ExternalText.Source.BUILD_OUTPUT) String verificationSummary,
        @ExternalText(ExternalText.Source.LLM_RESPONSE) String reviewSummary) {

    /** 🔴 재료 원문을 노출하지 않는다 (S-4). */
    @Override
    public String toString() {
        return "PrBodyMaterials[template=%d, change=%d, issueRef=%s, verification=%d, review=%d]"
                .formatted(length(template), length(changeSummary),
                        issueReference != null && !issueReference.isBlank(),
                        length(verificationSummary), length(reviewSummary));
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
