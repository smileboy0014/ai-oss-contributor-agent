package com.ossagent.agent.adapter.out.llm;

import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.AgentRunRecorder;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmException;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmResponse;
import com.ossagent.support.observability.LlmOutcome;
import com.ossagent.support.observability.PipelineMetrics;
import org.slf4j.MDC;

/**
 * 모든 LLM 호출을 실행 이력에 남기는 데코레이터. <b>이 클래스가 유일하게 노출되는
 * {@link LanguageModel} 빈이다.</b>
 *
 * <h2>왜 데코레이터인가</h2>
 *
 * <p>「호출마다 토큰을 기록한다」를 <b>규약으로 두면 언젠가 잊는다.</b> 소비자가
 * {@code complete()} 만 부르고 recorder 를 빼먹어도 컴파일되고 테스트도 통과한다.
 * 그 순간 비용이 보이지 않게 되고, 이 이슈가 없애려던 상태로 정확히 되돌아간다.
 *
 * <p>그래서 조립에서 <b>속 구현({@code AnthropicLanguageModel} · {@code DisabledLanguageModel})을
 * 빈으로 내보내지 않는다.</b> 주입받을 수 있는 것이 이것뿐이면 기록을 건너뛸 방법이 없다.
 *
 * <h2>실패도 남긴다</h2>
 *
 * <p>실패한 호출도 토큰을 먹는다. 타임아웃으로 끊긴 호출은 사용량을 못 받지만 모델은 이미
 * 생성했고 과금된다. 성공만 기록하면 <b>가장 비싼 경로가 장부에서 사라진다.</b>
 *
 * <p>절단({@link LlmFailureReason#TRUNCATED})은 응답을 받았으므로 사용량을 안다 —
 * 그 경우 실제 토큰을 기록한다.
 *
 * <p>MDC 3키는 {@code logging.md} 가 요구하는 것과 {@link AgentRunContext} 의 필드가
 * 정확히 1:1 이다. {@code finally} 에서 반드시 지운다 — 스레드가 재사용되면 남은 값이
 * 다음 요청 로그에 붙는다.
 */
public class RecordingLanguageModel implements LanguageModel {

    private final LanguageModel delegate;
    private final AgentRunRecorder recorder;
    private final PipelineMetrics metrics;

    public RecordingLanguageModel(LanguageModel delegate, AgentRunRecorder recorder,
            PipelineMetrics metrics) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.metrics = metrics;
    }

    @Override
    public LlmResponse complete(AgentRunContext ctx, LlmRequest request) {
        // 🔴 덮기 전에 이전 값을 챙긴다 — finally 에서 복원한다.
        //    remove 로 끝내면 「지운다」가 되어, 바깥(ScanPipelineUseCase·AnalyzeIssuesUseCase)이
        //    넣어 둔 stage·candidateId 가 첫 LLM 호출 이후 사라진다. 그러면 기각·실패 로그
        //    처럼 식별자가 가장 필요한 줄에서 MDC 가 비는데, 증상이 「로그가 조금 허전하다」뿐이라
        //    아무도 알아차리지 못한다 — 이 PR 이 고치려던 문제를 그대로 재현하는 셈이다
        String previousCandidateId = MDC.get("candidateId");
        String previousStage = MDC.get("stage");
        String previousAttempt = MDC.get("attempt");

        MDC.put("candidateId", String.valueOf(ctx.candidateId()));
        MDC.put("stage", ctx.callSite().name());
        MDC.put("attempt", String.valueOf(ctx.attempt()));
        try {
            Long runId = recorder.started(ctx);
            try {
                LlmResponse response = delegate.complete(ctx, request);
                recorder.succeeded(runId, response.usage());
                metrics.llmCall(ctx.callSite(), LlmOutcome.SUCCEEDED, response.usage(),
                        ctx.attempt());
                return response;
            } catch (LlmException e) {
                // 실패로 기록하되 아는 토큰은 함께 남긴다. 절단은 응답을 받았으므로 사용량을 안다 —
                // 성공으로 기록하면 장부가 거짓말을 하고, 사용량을 버리면 비용이 사라진다
                recorder.failed(runId, e.reason(), e.usage().orElse(null));
                // 🔴 실패도 토큰을 센다 — 절단은 응답을 받았으므로 사용량을 알고,
                //    모델은 이미 토큰을 생성했다. 성공만 세면 장부가 거짓말을 한다
                metrics.llmCall(ctx.callSite(), LlmOutcome.FAILED, e.usage().orElse(null),
                        ctx.attempt());
                throw e;
            } catch (RuntimeException e) {
                recorder.failed(runId, LlmFailureReason.INVALID_REQUEST, null);
                metrics.llmCall(ctx.callSite(), LlmOutcome.FAILED, null, ctx.attempt());
                throw e;
            }
        } finally {
            // 이전 값으로 되돌린다 — 없었으면 지운다.
            // ⚠ 풀 스레드는 재사용된다. 「없었으면 지운다」를 빠뜨리면 다음 실행의 로그에
            //   앞 실행의 값이 찍혀, 이어붙이려고 넣은 것이 잘못 이어붙이게 만든다.
            //
            // ⚠ restore(key, previous) 헬퍼로 묶지 않는다. 그러면 MDC.put 의 키가
            //   변수가 되어 MdcLogPatternTest 의 소스 스캐너가 「정적으로 알 수 없는 키」로
            //   판정한다 — 가드를 느슨하게 하느니 여기가 장황한 편이 낫다.
            //   실제로 헬퍼로 짰다가 그 가드에 잡혀 되돌렸다
            if (previousCandidateId == null) {
                MDC.remove("candidateId");
            } else {
                MDC.put("candidateId", previousCandidateId);
            }
            if (previousStage == null) {
                MDC.remove("stage");
            } else {
                MDC.put("stage", previousStage);
            }
            if (previousAttempt == null) {
                MDC.remove("attempt");
            } else {
                MDC.put("attempt", previousAttempt);
            }
        }
    }
}
