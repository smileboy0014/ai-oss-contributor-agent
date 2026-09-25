package com.ossagent.agent.adapter.out.llm;

import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmResponse;

/**
 * API 키가 없을 때 주입되는 구현. <b>호출하면 원인이 분명한 예외를 던진다.</b>
 *
 * <p>왜 빈을 아예 등록하지 않는 대신 이것을 두나 — 빈이 없으면 소비자가 필수 의존으로 받는
 * 순간 기본 설정으로 애플리케이션이 기동하지 않는다. 그러면 누군가 기본값을 뒤집거나
 * {@code required = false} 로 눕히게 되고, 후자면 <b>「조용히 통과」가 그대로 돌아온다.</b>
 *
 * <p>빈이 항상 있으면 키 없이도 기동하고(NFR-3), 테스트 컨텍스트에 실제 어댑터가 올라오지
 * 않으며(NFR-2), 배선 자체를 테스트로 확인할 수 있다.
 *
 * <p>⚠️ 이것은 <b>no-op 가 아니다.</b> 조용히 빈 응답을 돌려주면 파이프라인이 「모델이 아무 말도
 * 안 했다」로 진행한다. 반드시 실패한다.
 */
public class DisabledLanguageModel implements LanguageModel {

    @Override
    public LlmResponse complete(AgentRunContext ctx, LlmRequest request) {
        throw new IllegalStateException(
                "LLM 이 설정되지 않았다 — ANTHROPIC_API_KEY 가 비어 있다. callSite=" + ctx.callSite());
    }
}
