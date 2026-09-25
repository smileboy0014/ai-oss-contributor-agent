package com.ossagent.candidate.application;

import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.AgentRunRecorder;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmUsage;
import com.ossagent.candidate.adapter.out.persistence.AgentRunRepository;
import com.ossagent.candidate.domain.AgentRun;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM 실행 이력을 적재한다 — {@code agent} 가 선언한 {@link AgentRunRecorder} 의 구현.
 *
 * <h2>왜 {@code candidate} 가 구현하나</h2>
 *
 * <p>{@code AgentRun} 은 {@code candidate} 애그리거트 소유다. {@code agent} 가 직접 import 하면
 * 규율 ④ 위반이므로, {@code agent} 는 능력만 선언하고 여기서 구현한다.
 * 의존 방향이 {@code candidate → agent} 라 코드맵의 단방향 참조와 맞는다.
 *
 * <h2>왜 UseCase 인가 — 어댑터가 아니라</h2>
 *
 * <p>트랜잭션 경계는 UseCase 다({@code architecture.md} 계층 표). 어댑터에 {@code @Transactional}
 * 을 달면 경계가 두 군데로 흩어진다.
 *
 * <h2>왜 {@code REQUIRES_NEW} 가 아닌가</h2>
 *
 * <p>🔴 <b>기본 전파를 쓴다.</b> {@code REQUIRES_NEW} 는 「바깥 트랜잭션이 있다」를 전제하는데,
 * 바깥 트랜잭션이 있다는 것은 그 안에서 LLM 호출이 일어난다는 뜻이고 그것 자체가 위반이다.
 * {@code REQUIRES_NEW} 로 감싸면 <b>그 위반을 숨긴다.</b> 여기서는 짧은 트랜잭션 두 개로
 * 끝내고, 「호출 시점에 활성 트랜잭션이 없다」는 테스트로 단언한다.
 */
@Service
public class RecordAgentRunUseCase implements AgentRunRecorder {

    private final AgentRunRepository agentRuns;
    private final Clock clock;

    public RecordAgentRunUseCase(AgentRunRepository agentRuns, Clock clock) {
        this.agentRuns = agentRuns;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Long started(AgentRunContext ctx) {
        AgentRun run = AgentRun.start(ctx.candidateId(), toStage(ctx.callSite()), ctx.attempt(), clock);
        return agentRuns.save(run).getId();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void succeeded(Long runId, LlmUsage usage) {
        load(runId).succeed(usage.inputTokens(), usage.outputTokens(), clock);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void failed(Long runId, LlmFailureReason reason, LlmUsage usage) {
        // reason.name() 만 넣는다 — SDK 예외 원문은 여기까지 오지 않는다 (S-4)
        load(runId).fail(
                reason.name(),
                usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(),
                clock);
    }

    private AgentRun load(Long runId) {
        return agentRuns.findById(runId)
                .orElseThrow(() -> new IllegalStateException("실행 기록을 찾을 수 없다: id=" + runId));
    }

    /**
     * {@code agent} 의 호출 지점을 {@code candidate} 의 단계로 옮긴다.
     *
     * <p>이 매핑이 <b>여기</b> 있는 이유 — {@code agent} 가 {@code AgentRun.Stage} 를 알면
     * 규율 ④ 위반이다. {@code candidate} 가 {@code LlmCallSite} 를 아는 것은 허용 방향이다.
     *
     * <p>{@code VERIFY} 가 대응되지 않는 것은 정상이다. 검증은 샌드박스 단계이고 LLM 호출이 아니다.
     */
    private static AgentRun.Stage toStage(LlmCallSite callSite) {
        return switch (callSite) {
            case ANALYZE -> AgentRun.Stage.ANALYZE;
            case PLAN -> AgentRun.Stage.PLAN;
            case CODE -> AgentRun.Stage.CODE;
            case REVIEW -> AgentRun.Stage.REVIEW;
            // 저장소 단위 — candidateId 가 null 로 넘어온다 (#7)
            case POLICY -> AgentRun.Stage.POLICY;
        };
    }
}
