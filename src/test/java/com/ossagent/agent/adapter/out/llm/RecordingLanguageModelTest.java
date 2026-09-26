package com.ossagent.agent.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.support.observability.PipelineMetricsFixtures;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.FakeLanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmException;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmPermanentException;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmTransientException;
import com.ossagent.agent.domain.LlmUsage;
import com.ossagent.agent.domain.RecordingAgentRunRecorder;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 「호출마다 토큰을 기록한다」가 <b>규약이 아니라 구조</b>인지 본다.
 *
 * <p>비용이 보이지 않으면 재시도 루프가 조용히 돈을 태운다 — 이 이슈의 존재 이유이고,
 * 그래서 기록을 잊을 수 있는 형태로 두지 않는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RecordingLanguageModelTest {

    private static final AgentRunContext CTX =
            AgentRunContext.firstAttempt(42L, LlmCallSite.CODE);

    private static final LlmRequest REQUEST = new LlmRequest(null, "질문", 100);

    @org.junit.jupiter.api.AfterEach
    void MDC_를_비운다() {
        MDC.clear();
    }

    @Test
    void 바깥이_넣어_둔_MDC_를_지우지_않고_복원한다() {
        // 파이프라인(ScanPipelineUseCase)과 배치(AnalyzeIssuesUseCase)가 넣어 둔 값이다
        MDC.put("stage", "ANALYZE");
        MDC.put("candidateId", "7");

        new RecordingLanguageModel(new FakeLanguageModel().respondWith("응답", 1, 1),
                new RecordingAgentRunRecorder(), PipelineMetricsFixtures.discarding())
                .complete(CTX, REQUEST);

        assertThat(MDC.get("stage"))
                .as("finally 에서 remove 로 끝내면 복원이 아니라 삭제다. 그러면 첫 LLM 호출 "
                        + "이후 바깥 값이 사라져, 「후보 기각」·「호출 실패」처럼 식별자가 "
                        + "가장 필요한 줄에서 MDC 가 빈다 — 이 기능이 고치려던 문제를 "
                        + "그대로 재현하는 셈이다")
                .isEqualTo("ANALYZE");
        assertThat(MDC.get("candidateId")).isEqualTo("7");
    }

    @Test
    void 바깥에_없던_MDC_는_호출_후에도_없다() {
        // ⚠ 풀 스레드는 재사용된다. 「없었으면 지운다」를 빠뜨리면 다음 실행의 로그에
        //   앞 실행의 값이 찍혀, 이어붙이려고 넣은 것이 잘못 이어붙이게 만든다
        new RecordingLanguageModel(new FakeLanguageModel().respondWith("응답", 1, 1),
                new RecordingAgentRunRecorder(), PipelineMetricsFixtures.discarding())
                .complete(CTX, REQUEST);

        assertThat(MDC.get("stage")).isNull();
        assertThat(MDC.get("candidateId")).isNull();
        assertThat(MDC.get("attempt")).isNull();
    }

    @Test
    void 실패해도_MDC_가_복원된다() {
        MDC.put("stage", "ANALYZE");

        assertThatThrownBy(() -> new RecordingLanguageModel(
                new FakeLanguageModel().failWith(
                        new LlmTransientException(LlmFailureReason.TIMEOUT, LlmCallSite.CODE)),
                new RecordingAgentRunRecorder(), PipelineMetricsFixtures.discarding())
                .complete(CTX, REQUEST))
                .isInstanceOf(LlmException.class);

        assertThat(MDC.get("stage"))
                .as("실패 경로에서 복원이 빠지면 그 뒤 로그가 전부 단계 없이 찍힌다")
                .isEqualTo("ANALYZE");
    }

    @Test
    void 성공하면_토큰이_장부에_남는다() {
        var recorder = new RecordingAgentRunRecorder();
        var model = new RecordingLanguageModel(
                new FakeLanguageModel().respondWith("응답", 13, 17), recorder, PipelineMetricsFixtures.discarding());

        model.complete(CTX, REQUEST);

        assertThat(recorder.events()).containsExactly(
                "started:CODE:attempt=1",
                "succeeded:1:in=13:out=17");
    }

    @Test
    void 실패한_호출도_장부에_남는다() {
        var recorder = new RecordingAgentRunRecorder();
        var model = new RecordingLanguageModel(
                new FakeLanguageModel().failWith(
                        new LlmTransientException(LlmFailureReason.TIMEOUT, LlmCallSite.CODE)),
                recorder, PipelineMetricsFixtures.discarding());

        assertThatThrownBy(() -> model.complete(CTX, REQUEST))
                .isInstanceOf(LlmTransientException.class);

        assertThat(recorder.events())
                .as("성공만 기록하면 가장 비싼 경로가 장부에서 사라진다")
                .containsExactly("started:CODE:attempt=1", "failed:1:TIMEOUT:usage=unknown");
    }

    @Test
    void 절단은_실패로_남기되_아는_토큰을_함께_남긴다() {
        var recorder = new RecordingAgentRunRecorder();
        var model = new RecordingLanguageModel(
                new FakeLanguageModel().failWith(new LlmPermanentException(
                        LlmFailureReason.TRUNCATED, LlmCallSite.CODE, new LlmUsage(8, 4096))),
                recorder, PipelineMetricsFixtures.discarding());

        assertThatThrownBy(() -> model.complete(CTX, REQUEST))
                .isInstanceOf(LlmPermanentException.class);

        assertThat(recorder.events())
                .as("성공으로 기록하면 장부가 거짓말을 하고, 토큰을 버리면 비용이 사라진다")
                .containsExactly("started:CODE:attempt=1", "failed:1:TRUNCATED:out=4096");
    }

    @Test
    void 시작_기록이_대외_호출보다_먼저다() {
        var recorder = new RecordingAgentRunRecorder();
        var model = new RecordingLanguageModel(
                new FakeLanguageModel().failWith(
                        new LlmTransientException(LlmFailureReason.TIMEOUT, LlmCallSite.CODE)),
                recorder, PipelineMetricsFixtures.discarding());

        assertThatThrownBy(() -> model.complete(CTX, REQUEST)).isInstanceOf(LlmException.class);

        assertThat(recorder.events().get(0))
                .as("호출 전에 행을 만들지 않으면 타임아웃으로 죽은 호출의 비용이 통째로 사라진다")
                .startsWith("started:");
    }

    @Test
    void MDC_를_호출_후에_반드시_비운다() {
        var model = new RecordingLanguageModel(
                new FakeLanguageModel(), new RecordingAgentRunRecorder(), PipelineMetricsFixtures.discarding());

        model.complete(CTX, REQUEST);

        assertThat(MDC.get("candidateId"))
                .as("스레드가 재사용되면 남은 값이 다음 요청 로그에 붙는다")
                .isNull();
        assertThat(MDC.get("stage")).isNull();
        assertThat(MDC.get("attempt")).isNull();
    }

    @Test
    void 예외가_나도_MDC_를_비운다() {
        var model = new RecordingLanguageModel(
                new FakeLanguageModel().failWith(
                        new LlmTransientException(LlmFailureReason.TIMEOUT, LlmCallSite.CODE)),
                new RecordingAgentRunRecorder(), PipelineMetricsFixtures.discarding());

        assertThatThrownBy(() -> model.complete(CTX, REQUEST)).isInstanceOf(LlmException.class);

        assertThat(MDC.get("candidateId")).isNull();
    }
}
