package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.agent.domain.LlmTransientException;
import com.ossagent.agent.domain.SandboxPermanentException;
import com.ossagent.agent.domain.SandboxTransientException;
import com.ossagent.agent.domain.WorkspaceException;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 재시도 판정 — #21 · S-6.
 *
 * <h2>🔴 이 테스트가 지키는 것은 「판정표」가 아니라 <b>기본값의 방향</b>이다</h2>
 *
 * <p>「{@code CHANGES_REQUESTED} 면 재시도」 같은 개별 줄은 구현을 보면 알 수 있다.
 * 여기서 잡아야 하는 것은 <b>표에 없는 값이 생겼을 때 어디로 떨어지는가</b>다 —
 * 거부목록으로 짜면 그것이 조용히 <b>재시도</b>가 되고, 증상은 「비용이 3배」다.
 *
 * <p>그래서 {@link EnumSource} 로 <b>모든 값</b>을 돌리고, 화이트리스트 밖이 전부
 * {@link RetryDecision.Stop} 인 것을 단언한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RetryPolicyTest {

    // ── 🔴 요구 4: 입력 도달 — 이 가드가 보는 입력 공간이 실제와 같은가 ──────────

    /**
     * 🔴 <b>값이 늘면 여기가 먼저 빨개진다.</b>
     *
     * <p>{@code testing-philosophy.md} 요구 4 — 「열거로 정의한 가드는 열거에 없는 형태로
     * 샌다」. 아래 전수 테스트는 {@code values()} 를 돌므로 값이 늘어도 <b>자동으로</b>
     * 포함되지만, 그때 <b>판정표를 다시 봐야 한다는 사실</b>은 아무도 알려주지 않는다.
     * 이 단언이 그 알림이다.
     */
    @Test
    void 판정이_보는_값의_개수를_고정한다_S6() {
        assertThat(ReviewVerdict.values())
                .as("리뷰 판정 값이 늘었다 — RetryPolicy.after(DiffReview) 의 화이트리스트를 "
                        + "다시 본다. 새 값은 기본적으로 Stop 으로 떨어지는 것이 맞는지 확인한다")
                .hasSize(3);
        assertThat(StageOutcome.values())
                .as("검증 판정 값이 늘었다 — VerificationReport.passed()·hasUndetermined() 와 "
                        + "RetryPolicy.after(VerificationReport) 를 다시 본다")
                .hasSize(4);
        assertThat(CodingFeedback.Kind.values())
                .as("피드백 종류가 늘었다 — LlmCodingAgent 의 프롬프트 분기가 "
                        + "컴파일 에러로 먼저 잡히지만, 이 숫자가 그 사실을 기록한다")
                .hasSize(4);
    }

    // ── 리뷰 판정 — 전수 ────────────────────────────────────────────────────

    /**
     * 🔴 <b>화이트리스트 밖은 전부 {@code Stop} 이다.</b>
     *
     * <p>{@code UNDETERMINED} 가 재시도로 떨어지면 <b>고칠 수 없는 것에 3바퀴를 태운다</b> —
     * {@link ReviewVerdict} javadoc 이 그것을 「둘로 두면 방어가 스스로를 잠근다」로 적어 뒀다.
     */
    @ParameterizedTest
    @EnumSource(ReviewVerdict.class)
    void 리뷰_판정은_PASS만_통과_CHANGES_REQUESTED만_재시도다_S6(ReviewVerdict verdict) {
        RetryDecision decision = RetryPolicy.after(reviewWith(verdict));

        switch (verdict) {
            case PASS -> assertThat(decision).isInstanceOf(RetryDecision.Proceed.class);
            case CHANGES_REQUESTED -> assertThat(decision).isInstanceOf(RetryDecision.Retry.class);
            // 🔴 여집합. 값이 늘면 여기로 떨어지고, 그것이 옳다 — 모르는 것에 돈을 쓰지 않는다
            case UNDETERMINED -> assertThat(decision)
                    .as("판정 불가는 재시도 대상이 아니다 — 같은 입력에 같은 결과다")
                    .isInstanceOf(RetryDecision.Stop.class);
        }
    }

    @Test
    void 리뷰_재시도는_지적을_그대로_되먹인다() {
        DiffReview review = new DiffReview(ReviewVerdict.CHANGES_REQUESTED,
                false, true, null, true, "고칠 것이 있다", List.of("널 검사가 빠졌다"));

        RetryDecision decision = RetryPolicy.after(review);

        assertThat(decision).isInstanceOfSatisfying(RetryDecision.Retry.class, retry -> {
            assertThat(retry.feedback().kind()).isEqualTo(CodingFeedback.Kind.REVIEW);
            assertThat(retry.feedback().points()).containsExactly("널 검사가 빠졌다");
        });
    }

    // ── 검증 판정 ──────────────────────────────────────────────────────────

    @Test
    void 전부_PASSED_여야_통과다_S6() {
        RetryDecision decision = RetryPolicy.after(new VerificationReport(List.of(
                result(VerificationStage.COMPILE, StageOutcome.PASSED),
                result(VerificationStage.TEST, StageOutcome.PASSED),
                result(VerificationStage.DIFF, StageOutcome.PASSED))));

        assertThat(decision).isInstanceOf(RetryDecision.Proceed.class);
    }

    /**
     * 🔴 「실패가 없으면 통과」로 적으면 이 테스트가 빨개진다.
     *
     * <p>{@code UNDETERMINED} 뿐인 보고서는 {@code noneMatch(FAILED)} 로는 <b>통과</b>가 된다.
     * {@code VerificationReport} javadoc 의 표가 바로 이 자리다.
     */
    @Test
    void 판정_불가만_있어도_통과가_아니고_재시도도_아니다_S6() {
        RetryDecision decision = RetryPolicy.after(new VerificationReport(List.of(
                result(VerificationStage.COMPILE, StageOutcome.PASSED),
                result(VerificationStage.TEST, StageOutcome.UNDETERMINED),
                result(VerificationStage.DIFF, StageOutcome.SKIPPED))));

        assertThat(decision)
                .as("판정 불가는 재시도해도 같다 — Q-6 예산을 태우지 않는다")
                .isInstanceOfSatisfying(RetryDecision.Stop.class, stop -> {
                    assertThat(stop.stage()).isEqualTo(AgentRun.Stage.VERIFY);
                    // 🔴 어느 단계가 판정 불가였는지가 사람이 볼 유일한 신호다
                    assertThat(stop.reason()).contains("TEST");
                });
    }

    @Test
    void 실패한_단계가_있으면_그_단계로_재시도한다() {
        RetryDecision decision = RetryPolicy.after(new VerificationReport(List.of(
                result(VerificationStage.COMPILE, StageOutcome.PASSED),
                new StageResult(VerificationStage.TEST, StageOutcome.FAILED, 1,
                        Duration.ofSeconds(1), false, "2 tests failed"),
                StageResult.skipped(VerificationStage.DIFF))));

        assertThat(decision).isInstanceOfSatisfying(RetryDecision.Retry.class, retry ->
                assertThat(retry.feedback().kind()).isEqualTo(CodingFeedback.Kind.TEST));
    }

    /**
     * 🔴 <b>판정 불가가 실패보다 먼저다.</b>
     *
     * <p>둘이 섞인 보고서에서 순서를 뒤집으면 「고칠 수 있는 것이 하나라도 있으니 재시도」가
     * 되는데, 판정 불가 단계는 <b>다음 바퀴에도 그대로</b>라 3바퀴가 전부 같은 곳에서 멈춘다.
     */
    @Test
    void 실패와_판정_불가가_섞이면_멈춘다_S6() {
        RetryDecision decision = RetryPolicy.after(new VerificationReport(List.of(
                new StageResult(VerificationStage.COMPILE, StageOutcome.FAILED, 1,
                        Duration.ofSeconds(1), false, "컴파일 오류"),
                new StageResult(VerificationStage.TEST, StageOutcome.UNDETERMINED, null,
                        Duration.ZERO, false, "테스트 명령을 읽지 못했다"),
                StageResult.skipped(VerificationStage.DIFF))));

        assertThat(decision).isInstanceOf(RetryDecision.Stop.class);
    }

    // ── 예외 판정 ──────────────────────────────────────────────────────────

    /**
     * 🔴 <b>전송 계층 실패를 파이프라인 카운터로 세지 않는다.</b>
     *
     * <p>{@code agent.llm.max-retries} 가 이미 흡수했고, 여기까지 온 것은 그 상한이
     * 소진된 것이다. 또 세면 <b>두 축이 곱해진다</b>({@code architecture.md} §4).
     */
    @Test
    void 전송_실패는_재시도하지_않고_미룬다_S6() {
        RetryDecision decision = RetryPolicy.after(
                new LlmTransientException(LlmFailureReason.TIMEOUT, LlmCallSite.CODE));

        assertThat(decision)
                .as("재시도(Retry)가 아니다 — 두 축이 곱해진다. 그러나 종단(Stop)도 아니다 — 후보의 코드와 무관하다 (#98)")
                .isInstanceOfSatisfying(RetryDecision.Defer.class, defer ->
                        assertThat(defer.stage()).isEqualTo(AgentRun.Stage.CODE));
    }

    // ── 일시 장애는 미룬다 — 태우지 않는다 (#98) ──────────────────────────────

    /**
     * 🔴 이미지 없음·데몬 다운은 <b>후보의 코드와 무관하다.</b> 초안은 이것까지 {@code Stop} 으로
     * 보내 인프라 장애 한 번이 후보를 영구히 {@code FAILED} 로 지웠다.
     */
    @Test
    void 샌드박스_일시_장애는_미룬다_S6() {
        RetryDecision decision = RetryPolicy.after(
                new SandboxTransientException("이미지가 로컬에 없다"));

        assertThat(decision).isInstanceOfSatisfying(RetryDecision.Defer.class, defer ->
                assertThat(defer.stage()).isEqualTo(AgentRun.Stage.VERIFY));
    }

    /** 🔴 같은 계층이라도 <b>영구</b> 실패는 그대로 멈춘다 — 화이트리스트는 타입의 {@code retryable()} 을 본다. */
    @Test
    void 샌드박스_영구_실패는_멈춘다_S6() {
        RetryDecision decision = RetryPolicy.after(
                new SandboxPermanentException("경로가 루트 밖이다"));

        assertThat(decision).isInstanceOfSatisfying(RetryDecision.Stop.class, stop ->
                assertThat(stop.stage()).isEqualTo(AgentRun.Stage.VERIFY));
    }

    /** {@code WorkspaceException} 은 자기 javadoc 이 「코드가 깨졌다로 세지 않는다」고 못 박은 타입이다. */
    @Test
    void 워크스페이스_장애는_미룬다_S6() {
        RetryDecision decision = RetryPolicy.after(new WorkspaceException("clone 이 끊겼다"));

        assertThat(decision).isInstanceOf(RetryDecision.Defer.class);
    }

    @Test
    void 미룸_사유는_예외_본문을_싣지_않는다_S4() {
        RetryDecision decision = RetryPolicy.after(
                new WorkspaceException("https://user:secret@example.invalid/repo.git"));

        assertThat(decision).isInstanceOfSatisfying(RetryDecision.Defer.class, defer ->
                assertThat(defer.reason()).doesNotContain("secret").contains("WorkspaceException"));
    }

    @Test
    void 계획_밖_경로는_재시도하지_않는다() {
        RetryDecision decision = RetryPolicy.after(
                new CodingOutOfPlanException(7L, java.util.Set.of("build.gradle")));

        assertThat(decision).isInstanceOfSatisfying(RetryDecision.Stop.class, stop ->
                assertThat(stop.stage()).isEqualTo(AgentRun.Stage.CODE));
    }

    @Test
    void 리뷰_거부는_사유가_재시도_불가라_멈춘다() {
        RetryDecision decision = RetryPolicy.after(new DiffReviewRejectedException(
                DiffReviewRejectedException.Reason.TOO_LARGE, "diff 가 너무 크다"));

        assertThat(decision).isInstanceOfSatisfying(RetryDecision.Stop.class, stop ->
                assertThat(stop.stage()).isEqualTo(AgentRun.Stage.REVIEW));
    }

    /**
     * 🔴 <b>모르는 예외는 멈춘다</b> — 화이트리스트의 정의.
     *
     * <p>이 테스트가 「거부목록으로 짜지 않았다」의 증거다. {@code RuntimeException} 은
     * 어떤 목록에도 없고, 그래서 <b>재시도로 떨어지면 안 된다.</b>
     */
    @Test
    void 모르는_예외는_멈춘다_화이트리스트_S6() {
        RetryDecision decision = RetryPolicy.after(new IllegalStateException("처음 보는 실패"));

        assertThat(decision)
                .as("기본값이 재시도면 새 실패 종류마다 비용이 3배가 된다")
                .isInstanceOf(RetryDecision.Stop.class);
    }

    // ── 같은 실패 반복 (FR-5) ───────────────────────────────────────────────

    @Test
    void 같은_실패가_반복되면_조기_중단한다() {
        CodingFeedback feedback = new CodingFeedback(
                CodingFeedback.Kind.TEST, List.of("같은 테스트가 또 깨졌다"));
        RetryDecision retry = new RetryDecision.Retry(feedback);

        RetryDecision guarded = RetryPolicy.guardRepeat(
                retry, Optional.of(FailureFingerprint.of(feedback)));

        assertThat(guarded).isInstanceOfSatisfying(RetryDecision.Stop.class, stop ->
                assertThat(stop.stage()).isEqualTo(AgentRun.Stage.VERIFY));
    }

    @Test
    void 다른_실패면_재시도를_유지한다() {
        RetryDecision retry = new RetryDecision.Retry(
                new CodingFeedback(CodingFeedback.Kind.TEST, List.of("이번엔 다른 테스트")));

        RetryDecision guarded = RetryPolicy.guardRepeat(retry,
                Optional.of(FailureFingerprint.of(
                        new CodingFeedback(CodingFeedback.Kind.TEST, List.of("앞의 테스트")))));

        assertThat(guarded).isInstanceOf(RetryDecision.Retry.class);
    }

    @Test
    void 첫_바퀴는_비교할_지문이_없어_그대로_간다() {
        RetryDecision retry = new RetryDecision.Retry(
                new CodingFeedback(CodingFeedback.Kind.COMPILE, List.of("오류")));

        assertThat(RetryPolicy.guardRepeat(retry, Optional.empty()))
                .isSameAs(retry);
    }

    /** 통과·종단에는 지문 판정이 끼어들지 않는다 — 끼어들면 통과가 중단으로 바뀐다. */
    @Test
    void 통과는_지문_판정을_타지_않는다() {
        RetryDecision proceed = new RetryDecision.Proceed();

        assertThat(RetryPolicy.guardRepeat(proceed, Optional.of(
                new FailureFingerprint("deadbeef0000")))).isSameAs(proceed);
    }

    // ── 픽스처 ─────────────────────────────────────────────────────────────

    private static DiffReview reviewWith(ReviewVerdict verdict) {
        // ⚠ CHANGES_REQUESTED 는 findings 가 비면 생성자가 거부한다 — 그것도 계약이다
        List<String> findings = verdict == ReviewVerdict.CHANGES_REQUESTED
                ? List.of("고칠 것")
                : List.of();
        return new DiffReview(verdict, null, null, null, null, "요약", findings);
    }

    private static StageResult result(VerificationStage stage, StageOutcome outcome) {
        return new StageResult(stage, outcome, 0, Duration.ofSeconds(1), false, "출력");
    }
}
