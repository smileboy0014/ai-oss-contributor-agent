package com.ossagent.agent.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmRequest;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 키가 없을 때의 동작.
 *
 * <p>NFR-3(키 없이 기동)은 이 클래스가 존재함으로써 충족된다 — 조건부 빈 등록 대신
 * 「항상 빈은 있고 호출할 때 실패한다」를 택한 이유는 {@link DisabledLanguageModel} javadoc 에 있다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DisabledLanguageModelTest {

    @Test
    void 호출하면_원인이_분명한_예외를_던진다() {
        assertThatThrownBy(() -> new DisabledLanguageModel().complete(
                AgentRunContext.firstAttempt(1L, LlmCallSite.ANALYZE),
                new LlmRequest(null, "질문", 100)))
                .as("조용히 빈 응답을 돌려주면 파이프라인이 「모델이 아무 말도 안 했다」로 진행한다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void 프롬프트를_읽지_않는다_S4() {
        // 스크럽을 거치지 않는 경로지만 request 를 한 번도 읽지 않으므로 송신도 유출도 없다.
        // 「스크럽 누락」이 아니라 「해당 없음」이라는 것을 여기서 고정한다
        String leaked = "ghp_" + "C".repeat(36);

        assertThatThrownBy(() -> new DisabledLanguageModel().complete(
                AgentRunContext.firstAttempt(1L, LlmCallSite.CODE),
                new LlmRequest(leaked, leaked, 100)))
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage())
                        .as("예외 메시지에 프롬프트가 실리면 로그로 그대로 나간다")
                        .doesNotContain(leaked));
    }
}
