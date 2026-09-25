package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.agent.adapter.out.llm.RecordingLanguageModel;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmResponse;
import com.ossagent.agent.domain.LlmUsage;
import com.ossagent.candidate.adapter.out.persistence.AgentRunRepository;
import com.ossagent.candidate.domain.AgentRun;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 실행 이력 적재가 <b>대외 호출을 트랜잭션 안에 끌어들이지 않는지</b> 본다.
 *
 * <p>🔴 이 프로젝트의 「트랜잭션 안에서 대외 호출 금지」 규율이 <b>처음 실질적 의미를 갖는
 * 지점</b>이다. 샌드박스는 최대 30분이고 LLM 도 분 단위가 될 수 있다. 기록을 한 트랜잭션으로
 * 감싸면 그 시간 내내 DB 커넥션이 잡힌다.
 */
@SpringBootTest
@TestPropertySource(properties = "agent.llm.api-key=")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RecordAgentRunUseCaseTest {

    @Autowired
    private RecordAgentRunUseCase recorder;

    @Autowired
    private AgentRunRepository agentRuns;

    private static final AgentRunContext CTX =
            AgentRunContext.firstAttempt(9001L, LlmCallSite.ANALYZE);

    @Test
    void LLM_호출_시점에_활성_트랜잭션이_없다() {
        var sawTransaction = new AtomicBoolean(true);
        LanguageModel probe = (ctx, request) -> {
            sawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new LlmResponse("응답", new LlmUsage(1, 2));
        };

        new RecordingLanguageModel(probe, recorder)
                .complete(CTX, new LlmRequest(null, "질문", 100));

        assertThat(sawTransaction)
                .as("기록을 한 트랜잭션으로 감싸면 대외 호출 내내 DB 커넥션이 잡힌다")
                .isFalse();
    }

    @Test
    void 시작과_종료가_각각_커밋되어_조회된다() {
        var model = new RecordingLanguageModel(
                (ctx, request) -> new LlmResponse("응답", new LlmUsage(13, 17)), recorder);

        model.complete(CTX, new LlmRequest(null, "질문", 100));

        AgentRun saved = agentRuns.findAll().stream()
                .filter(run -> run.getCandidateId().equals(9001L))
                .reduce((first, second) -> second)
                .orElseThrow();

        assertThat(saved.getStatus()).isEqualTo(AgentRun.RunStatus.SUCCEEDED);
        assertThat(saved.getInputTokens()).isEqualTo(13);
        assertThat(saved.getOutputTokens()).isEqualTo(17);
        assertThat(saved.getStage()).isEqualTo(AgentRun.Stage.ANALYZE);
    }
}
