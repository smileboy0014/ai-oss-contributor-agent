package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import java.util.List;

/**
 * 직전 바퀴가 <b>왜 실패했는가</b> — 다음 바퀴의 코딩 프롬프트에 되먹이는 값 (#21 · FR-3).
 *
 * <h2>🔴 「Error Analyzer」는 LLM 호출이 아니다</h2>
 *
 * <p>PRD §17 다이어그램의 {@code Error Analyzer} 는 <b>분기점</b>이지 모델 호출이 아니다.
 * 호출로 만들면 바퀴마다 하나가 더 붙어 곱셈 예산이 <b>9회 → 18회</b>가 된다 —
 * {@code architecture.md} §4 가 「전송 상한을 올릴 때는 이 곱을 먼저 계산한다」고 둔 이유다.
 *
 * <p>그래서 분석은 <b>순수 변환</b>이다. 실패한 단계와 이미 스크럽된 지적을 모아
 * {@link Kind} 로 분류하는 것이 전부이고, 판정은 {@link RetryPolicy} 가 한다.
 *
 * <h2>🔴 스크럽된 값만 담는다 — S-4</h2>
 *
 * <p>여기 실리는 것은 <b>대상 저장소 빌드 출력</b>과 <b>모델 응답</b>이다. 둘 다 시크릿이
 * 섞일 수 있는 외부 텍스트이고, 이 값은 다시 <b>LLM 프롬프트로 나간다.</b>
 *
 * <p>그래서 원문을 받지 않는다. 출처가 둘뿐이고 <b>양쪽 다 이미 생성자에서 스크럽이
 * 강제</b>된다.
 *
 * <table border="1">
 *   <caption>담을 수 있는 것은 이 둘뿐이다</caption>
 *   <tr><th>출처</th><th>스크럽 강제 지점</th></tr>
 *   <tr><td>{@code StageResult.summary}</td><td>compact 생성자 (#19)</td></tr>
 *   <tr><td>{@code DiffReview.findings}</td><td>compact 생성자 (#20)</td></tr>
 * </table>
 *
 * <p>⚠️ <b>그래서 여기서 다시 스크럽하지 않는다.</b> 같은 방어를 두 벌 두면 어느 쪽이
 * 진짜인지 흐려지고, 한쪽만 고쳐지는 날이 온다 — {@code SelectedFile} 이 「값 타입이
 * 강제 지점」으로 간 것과 같은 판단이다. 🔴 다만 그 전제가 깨지면 <b>여기가 유출구가
 * 된다</b>: {@code String} 을 그대로 받는 새 생성 경로를 만들지 않는다.
 *
 * @param kind   무엇이 실패했나 — 프롬프트가 이것으로 갈린다
 * @param points 고쳐야 할 것들. <b>이미 스크럽된</b> 값만
 */
public record CodingFeedback(
        Kind kind,
        @ExternalText(ExternalText.Source.LLM_RESPONSE) List<String> points) {

    /** 되먹일 지적 개수 상한. 프롬프트가 상한 없이 자라는 것을 막는다. */
    private static final int MAX_POINTS = 20;

    public CodingFeedback {
        if (kind == null) {
            throw new IllegalArgumentException("피드백 종류는 필수다 — 프롬프트가 이것으로 갈린다");
        }
        if (points == null || points.isEmpty()) {
            // 🔴 「고쳐라」라고만 하고 무엇을 고칠지 없으면 다음 바퀴가 할 수 있는 것이 없다.
            //    그것은 재시도 예산만 태우는 피드백이다 — DiffReview 가 CHANGES_REQUESTED 에
            //    findings 를 요구하는 것과 같은 이유다
            throw new IllegalArgumentException(
                    "되먹일 지적이 없다 — 고칠 대상 없는 재시도는 예산만 태운다 kind=" + kind);
        }
        points = List.copyOf(points.size() > MAX_POINTS ? points.subList(0, MAX_POINTS) : points);
    }

    /**
     * 검증 단계의 실패를 피드백으로 — {@code COMPILE}·{@code TEST}·{@code DIFF}.
     *
     * <p>🔴 <b>{@code summary} 는 이미 스크럽·절단됐다</b>({@code StageResult} compact 생성자).
     * 여기서 여러 단계의 출력을 이어 붙이지 않는 이유도 같다 — 프롬프트가 상한 없이 자란다.
     * 검증은 첫 실패에서 멈추므로 실제로 내용이 있는 단계는 하나뿐이기도 하다.
     */
    public static CodingFeedback of(StageResult stage) {
        if (stage == null) {
            throw new IllegalArgumentException("단계 결과는 필수다");
        }
        String summary = stage.summary();
        if (summary == null || summary.isBlank()) {
            // 사유 없는 실패는 되먹일 것이 없다. 그것은 재시도가 아니라 사람이 볼 일이다
            throw new IllegalArgumentException(
                    "실패 요약이 비어 있다 — 되먹일 것이 없다 stage=" + stage.stage());
        }
        return new CodingFeedback(Kind.of(stage.stage()), List.of(summary));
    }

    /** 리뷰 지적을 피드백으로. {@code findings} 는 항목마다 스크럽됐다(#20). */
    public static CodingFeedback of(DiffReview review) {
        if (review == null) {
            throw new IllegalArgumentException("리뷰 결과는 필수다");
        }
        return new CodingFeedback(Kind.REVIEW, review.findings());
    }

    /**
     * 무엇이 실패했나. <b>프롬프트가 이것으로 갈린다</b> — FR-3 「구분해 다른 프롬프트로」.
     *
     * <p>⚠️ 값을 더하면 {@code LlmCodingAgent} 의 프롬프트 분기와
     * {@code CodingFeedbackTest} 의 모수 단언이 함께 빨개진다 — 의도다.
     */
    public enum Kind {

        /** 컴파일이 깨졌다. 가장 기계적이고, 고칠 대상이 가장 분명하다 */
        COMPILE,

        /** 테스트가 깨졌다. ⚠️ 대상 저장소 테스트가 네트워크를 요구해서일 수도 있다 — Q-4 */
        TEST,

        /** 계획 밖 파일이 바뀌었다 */
        DIFF,

        /** AI 리뷰가 변경을 요구했다 */
        REVIEW;

        /**
         * 검증 단계 → 피드백 종류. 🔴 <b>{@code switch} 의 {@code default} 를 두지 않는다.</b>
         *
         * <p>{@link VerificationStage} 에 값이 추가되면 <b>컴파일이 깨진다</b> — 그것이
         * 목적이다. {@code default} 를 두면 새 단계가 조용히 아무 종류로나 분류되고,
         * 프롬프트가 엉뚱한 것을 고치라고 말한다.
         */
        static Kind of(VerificationStage stage) {
            return switch (stage) {
                case COMPILE -> COMPILE;
                case TEST -> TEST;
                case DIFF -> DIFF;
            };
        }
    }

    /** 🔴 내용을 담지 않는다. 실수로 로그에 실려도 빌드 출력·리뷰 원문이 나가지 않게 한다. */
    @Override
    public String toString() {
        return "CodingFeedback[kind=%s, points=%d]".formatted(kind, points.size());
    }
}
