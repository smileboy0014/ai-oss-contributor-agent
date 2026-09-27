package com.ossagent.candidate.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;
import java.time.Duration;

/**
 * 검증 한 단계의 결과 — #19.
 *
 * <h2>🔴 {@code summary} 는 빌드 출력이다 — 생성자가 스크럽을 강제한다 (S-4)</h2>
 *
 * <p>대상 저장소의 빌드 스크립트는 <b>환경변수를 찍는 것이 흔하다.</b>
 * 그리고 이 값은 세 곳으로 나간다 — 로그 · {@code GeneratedChange.testResult}(DB) ·
 * <b>재시도 프롬프트</b>(Q-6 의 {@code CODE→VERIFY→REVIEW} 루프가 실패 사유를 모델에
 * 되먹인다). 한 곳만 막으면 나머지 둘로 샌다.
 *
 * <p>그래서 <b>{@code String} 을 그대로 받는 생성 경로를 두지 않았다</b> —
 * {@code ScrubbedRules}(#7) · {@code SelectedFile}(#15) 와 같은 수법이다.
 * 호출자가 {@code redact} 를 기억해야 하는 구조였으면 언젠가 빠진다.
 *
 * <p>⚠️ 보장하는 것은 <b>「스크럽을 거치지 않은 값이 들어갈 수 없다」</b>이지
 * 「내용이 깨끗하다」가 아니다. {@code TokenRedactor} 는 <b>아는 패턴만</b> 가린다.
 *
 * <h2>{@code exitCode} 가 {@code Integer} 인 이유</h2>
 *
 * <p>{@code -1} 같은 초병값을 두지 않는다 — 「돌리지 않았다」와 「종료코드 -1」을
 * 구분하지 못하게 되고, 그 혼동은 {@link StageOutcome#SKIPPED} 를 둔 이유를 무너뜨린다.
 *
 * @param stage           어느 단계인가
 * @param outcome         판정
 * @param exitCode        종료코드. 🔴 돌리지 않았으면 {@code null}
 * @param duration        소요 시간. 돌리지 않았으면 {@link Duration#ZERO}
 * @param outputTruncated 출력이 {@code sandbox.max-output-chars} 에서 잘렸는가.
 *                        🔴 <b>{@code DIFF} 의 판정 근거</b>이고, 나머지 단계에서는
 *                        참고값이다 — {@link StageOutcome} javadoc
 * @param summary         스크럽·절단된 출력 요약
 */
public record StageResult(
        VerificationStage stage,
        StageOutcome outcome,
        Integer exitCode,
        Duration duration,
        boolean outputTruncated,
        @ExternalText(ExternalText.Source.BUILD_OUTPUT) String summary) {

    /**
     * 요약 상한.
     *
     * <p>🔴 <b>{@code sandbox.max-output-chars}(200,000)와 다른 값이고, 그래야 한다.</b>
     * 저쪽은 「컨테이너 출력을 얼마나 읽을까」이고 이쪽은 「DB 행과 프롬프트에 얼마나 실을까」다.
     * 같은 값으로 묶으면 샌드박스 버퍼를 키우는 순간 <b>재시도 프롬프트가 함께 부풀어</b>
     * 토큰 비용이 조용히 늘어난다.
     */
    public static final int MAX_SUMMARY_LENGTH = 8_000;

    /** 상한을 넘겨 잘렸을 때 머리에 붙는 표시. 사람이 「원본이 더 있다」를 알아야 한다. */
    private static final String TRUNCATION_MARK = "…(요약 절단)\n";

    public StageResult {
        if (stage == null || outcome == null) {
            throw new IllegalArgumentException("검증 단계와 판정은 필수다");
        }
        if (duration == null || duration.isNegative()) {
            duration = Duration.ZERO;
        }
        // 🔴 유일한 생성 경로가 스크럽을 탄다 — S-4. 순서가 중요하다:
        //    먼저 가리고 그 다음에 자른다. 반대로 하면 잘린 토큰 조각이 패턴에 안 걸려
        //    그대로 남는다
        summary = truncate(TokenRedactor.redact(summary == null ? "" : summary));
    }

    private static String truncate(String scrubbed) {
        if (scrubbed.length() <= MAX_SUMMARY_LENGTH) {
            return scrubbed;
        }
        // 🔴 앞이 아니라 뒤를 남긴다. 빌드 실패 이유는 로그 끝에 있다 —
        //    앞 8,000자는 의존성 해석 로그로 채워져 진단에 쓸모가 없다
        int keep = MAX_SUMMARY_LENGTH - TRUNCATION_MARK.length();
        return TRUNCATION_MARK + scrubbed.substring(scrubbed.length() - keep);
    }

    public static StageResult skipped(VerificationStage stage) {
        return new StageResult(stage, StageOutcome.SKIPPED, null, Duration.ZERO, false, "");
    }

    public boolean isPassed() {
        return outcome.isPassed();
    }

    /**
     * 🔴 <b>{@code summary} 를 찍지 않는다.</b> 대상 저장소 빌드 출력이라
     * 포맷 문자열에 넣으면 로그 인젝션 경로가 된다 — {@code logging.md}.
     */
    @Override
    public String toString() {
        return "StageResult[stage=%s, outcome=%s, exitCode=%s, durationMs=%d, truncated=%s, summarySize=%d]"
                .formatted(stage, outcome, exitCode, duration.toMillis(), outputTruncated,
                        summary.length());
    }
}
