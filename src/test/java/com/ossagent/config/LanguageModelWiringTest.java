package com.ossagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.agent.adapter.out.llm.AnthropicLanguageModel;
import com.ossagent.agent.adapter.out.llm.RecordingLanguageModel;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmCallSite;
import com.ossagent.agent.domain.LlmRequest;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 배선 자체를 검증한다 — NFR-2 · NFR-3.
 *
 * <p>{@code agent.llm.api-key} 를 <b>명시적으로 비운다.</b> 개발자 머신에
 * {@code ANTHROPIC_API_KEY} 가 export 돼 있어도 테스트 결과가 달라지지 않아야 한다.
 * 환경변수 유무로 결과가 갈리면 이 테스트는 아무것도 보장하지 못한다.
 */
@SpringBootTest
@TestPropertySource(properties = "agent.llm.api-key=")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LanguageModelWiringTest {

    @Autowired
    private LanguageModel languageModel;

    @Test
    void 키가_있는_머신에서도_테스트_컨텍스트에_실제_어댑터가_없다_S4() {
        assertThat(languageModel)
                .as("컨텍스트에 실제 어댑터가 올라오면 테스트가 네트워크를 탄다")
                .isNotInstanceOf(AnthropicLanguageModel.class);
    }

    @Test
    void 주입되는_것은_항상_기록_데코레이터다() {
        assertThat(languageModel)
                .as("속 구현이 빈으로 나가면 기록을 건너뛰고 LLM 을 부를 수 있게 된다")
                .isInstanceOf(RecordingLanguageModel.class);
    }

    @Test
    void 키가_없어도_기동하고_호출할_때_비로소_실패한다() {
        // 기동은 됐다(이 테스트가 도는 것 자체가 증거) — NFR-3
        assertThatThrownBy(() -> languageModel.complete(
                AgentRunContext.firstAttempt(1L, LlmCallSite.ANALYZE),
                new LlmRequest(null, "질문", 100)))
                .as("조용히 빈 응답을 돌려주면 파이프라인이 「모델이 아무 말도 안 했다」로 진행한다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }
}
