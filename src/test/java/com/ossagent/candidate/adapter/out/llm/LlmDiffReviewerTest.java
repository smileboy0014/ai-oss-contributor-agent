package com.ossagent.candidate.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossagent.agent.adapter.out.llm.RecordingLanguageModel;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.AgentRunRecorder;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmResponse;
import com.ossagent.agent.domain.LlmUsage;
import com.ossagent.candidate.application.DiffReviewProperties;
import com.ossagent.candidate.domain.DiffReview;
import com.ossagent.candidate.domain.DiffReviewRejectedException;
import com.ossagent.candidate.domain.DiffReviewRequest;
import com.ossagent.candidate.domain.ReviewVerdict;
import com.ossagent.issue.domain.AnalyzableIssue;
import com.ossagent.issue.domain.FilterOutcome;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.support.observability.PipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * diff 리뷰 어댑터 — 요청 조립과 응답 파싱만 본다. <b>실제 LLM 을 타지 않는다</b>(Q-9).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LlmDiffReviewerTest {

    private static final AgentRunContext CONTEXT =
            AgentRunContext.firstAttempt(7L, LlmCallSite.REVIEW);

    @Test
    void diff_가_상한을_넘으면_자르지_않고_거부한다() {
        LlmDiffReviewer reviewer = reviewer(answer("PASS"), new DiffReviewProperties(10, 1000));

        assertThatThrownBy(() -> reviewer.review(CONTEXT, request("x".repeat(11))))
                .as("잘린 diff 를 리뷰하면 「안 본 부분에 문제가 있었을 수 있다」가 된다 — "
                        + "#7 이 규약 문서에서 내린 판단과 같다")
                .isInstanceOf(DiffReviewRejectedException.class)
                .extracting(e -> ((DiffReviewRejectedException) e).reason())
                .isEqualTo(DiffReviewRejectedException.Reason.TOO_LARGE);
    }

    @Test
    void 깨진_응답은_거부한다() {
        LlmDiffReviewer reviewer = reviewer((ctx, req) -> new LlmResponse("JSON 이 아님", usage()),
                DiffReviewProperties.defaults());

        assertThatThrownBy(() -> reviewer.review(CONTEXT, request("diff")))
                .isInstanceOf(DiffReviewRejectedException.class)
                .extracting(e -> ((DiffReviewRejectedException) e).reason())
                .isEqualTo(DiffReviewRejectedException.Reason.SCHEMA);
    }

    @Test
    void 코드펜스를_붙여_와도_읽는다() {
        LlmDiffReviewer reviewer = reviewer(
                (ctx, req) -> new LlmResponse("```json\n" + body("PASS") + "\n```", usage()),
                DiffReviewProperties.defaults());

        assertThat(reviewer.review(CONTEXT, request("diff")).passed()).isTrue();
    }

    @Test
    void 규약을_모르면_모델이_뭐라_하든_판정_불가로_고정한다_S5() {
        // 🔴 프롬프트로 부탁만 하고 믿으면, 모델이 자기 상식으로 판정한 것이
        //    「위반 없음」으로 DB 에 앉는다 — 「모른다」가 「문제 없음」이 되는 자리다
        LlmDiffReviewer reviewer = reviewer((ctx, req) -> new LlmResponse(body("PASS"), usage()),
                DiffReviewProperties.defaults());

        DiffReview review = reviewer.review(CONTEXT, new DiffReviewRequest(
                "diff", issue(), ContributionConstraints.unknown()));

        assertThat(review.followsConventions())
                .as("모델은 followsConventions=true 로 답했다. 규약을 모르는데 그것을 믿을 수 없다")
                .isNull();
    }

    @Test
    void 규약을_알면_모델_판정을_그대로_싣는다_S5() {
        LlmDiffReviewer reviewer = reviewer((ctx, req) -> new LlmResponse(body("PASS"), usage()),
                DiffReviewProperties.defaults());

        assertThat(reviewer.review(CONTEXT, request("diff")).followsConventions()).isTrue();
    }

    @Test
    void 알_수_없는_판정값은_판정_불가로_떨어뜨리지_않는다() {
        // 모델의 오타가 UNDETERMINED 로 둔갑하면 재시도 없이 조용히 넘어간다. 그것은 응답 오류다
        LlmDiffReviewer reviewer = reviewer((ctx, req) -> new LlmResponse(body("MAYBE"), usage()),
                DiffReviewProperties.defaults());

        assertThatThrownBy(() -> reviewer.review(CONTEXT, request("diff")))
                .isInstanceOf(DiffReviewRejectedException.class);
    }

    @Test
    void LLM_호출은_AgentRun_에_기록된다_FR6() {
        // 🔴 데코레이터가 기록하므로 어댑터가 기록 코드를 갖지 않는다.
        //    ⚠ fakes 프로파일에서는 RecordingLanguageModel 이 빠지므로 여기서
        //      운영 체인을 손으로 조립한다 — #25 가 쓴 수법이다
        RecordingRuns runs = new RecordingRuns();
        LanguageModel recording = new RecordingLanguageModel(
                (ctx, req) -> new LlmResponse(body("PASS"), usage()), runs,
                new PipelineMetrics(new SimpleMeterRegistry()), null);

        new LlmDiffReviewer(recording, DiffReviewProperties.defaults(), new ObjectMapper())
                .review(CONTEXT, request("diff"));

        assertThat(runs.started)
                .as("비용이 보이지 않으면 재시도 루프가 조용히 돈을 태운다")
                .containsExactly(LlmCallSite.REVIEW);
        assertThat(runs.succeeded).isEqualTo(1);
    }

    @Test
    void 자바_버전만_아는_것은_규약을_아는_것이_아니다_S5() {
        // 🔴 javaVersion 은 **빌드 대상**이지 기여 관습이 아니다. 이것 하나로 「규약을 안다」가
        //    되면 sign-off·이슈 참조·테스트 동반을 하나도 모르는데 모델의 true 를 믿게 된다
        LlmDiffReviewer reviewer = reviewer((ctx, req) -> new LlmResponse(body("PASS"), usage()),
                DiffReviewProperties.defaults());

        DiffReview review = reviewer.review(CONTEXT, new DiffReviewRequest("diff", issue(),
                new ContributionConstraints("21", null, null, false, false, false)));

        assertThat(review.followsConventions())
                .as("모델은 true 로 답했다. 관습에 대해 아는 것이 없으면 믿을 수 없다")
                .isNull();
    }

    @Test
    void 관습_필드가_하나라도_있으면_규약을_아는_것이다_S5() {
        // 반대 방향도 고정한다 — 이 판정이 항상 null 을 내면 관습 축이 영영 죽는다
        LlmDiffReviewer reviewer = reviewer((ctx, req) -> new LlmResponse(body("PASS"), usage()),
                DiffReviewProperties.defaults());

        DiffReview review = reviewer.review(CONTEXT, new DiffReviewRequest("diff", issue(),
                new ContributionConstraints(null, null, null, false, false, true)));

        assertThat(review.followsConventions())
                .as("sign-off 필수를 안다 — 관습을 판정할 근거가 있다")
                .isTrue();
    }

    @Test
    void 문자열이_아닌_지적_원소는_사유를_정확히_말하며_거부한다() {
        LlmDiffReviewer reviewer = reviewer((ctx, req) -> new LlmResponse("""
                {"verdict":"CHANGES_REQUESTED","satisfiesIssue":false,"withinScope":true,
                 "followsConventions":true,"testsAdequate":true,
                 "summary":"고칠 것","findings":[{"note":"객체다"}]}""", usage()),
                DiffReviewProperties.defaults());

        assertThatThrownBy(() -> reviewer.review(CONTEXT, request("diff")))
                .as("막는 것과 「왜 막혔는지 정확히 말하는 것」은 다른 일이다")
                .isInstanceOf(DiffReviewRejectedException.class)
                .hasMessageContaining("findings 원소");
    }

    // ─────────────────────── 도우미 ───────────────────────

    private static LlmDiffReviewer reviewer(LanguageModel model, DiffReviewProperties properties) {
        return new LlmDiffReviewer(model, properties, new ObjectMapper());
    }

    private static LanguageModel answer(String verdict) {
        return (ctx, req) -> new LlmResponse(body(verdict), usage());
    }

    private static String body(String verdict) {
        return """
                {"verdict":"%s","satisfiesIssue":true,"withinScope":true,
                 "followsConventions":true,"testsAdequate":true,
                 "summary":"검토했다","findings":[]}""".formatted(verdict);
    }

    private static DiffReviewRequest request(String diff) {
        return new DiffReviewRequest(diff, issue(),
                new ContributionConstraints("21", "./gradlew build", "./gradlew test",
                        true, true, true));
    }

    private static AnalyzableIssue issue() {
        return new AnalyzableIssue(1L, 2L, 42, "제목", "본문", List.of(),
                "https://example.test/issues/42", FilterOutcome.PASSED, (short) 0);
    }

    private static LlmUsage usage() {
        return new LlmUsage(10, 20);
    }

    /** {@code AgentRun} 기록을 세는 최소 구현. */
    private static final class RecordingRuns implements AgentRunRecorder {
        private final List<LlmCallSite> started = new ArrayList<>();
        private int succeeded;

        @Override
        public Long started(AgentRunContext ctx) {
            started.add(ctx.callSite());
            return (long) started.size();
        }

        @Override
        public void succeeded(Long runId, LlmUsage usage) {
            succeeded++;
        }

        @Override
        public void failed(Long runId, LlmFailureReason reason, LlmUsage usage) {
            // 이 테스트에서는 쓰지 않는다
        }
    }
}
