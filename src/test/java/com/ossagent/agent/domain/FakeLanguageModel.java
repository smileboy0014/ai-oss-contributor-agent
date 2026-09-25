package com.ossagent.agent.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 능력 소비자 층의 대역 — 고정 응답.
 *
 * <p>Q-9 이 정한 3계층 중 맨 위다. 여기서 보는 것은 <b>계약이 실제로 대체 가능한가</b>와
 * 업무 흐름이지, HTTP 가 아니다. 능력 인터페이스가 domain 에 있어 이렇게 쉽게 만들 수 있는 것이
 * 규율 ③(능력은 domain 이 선언)의 실질적 이득이다.
 *
 * <p>이름은 {@code Fake{능력이름}} — {@code FakeRepositorySource}·{@code FakeIssueSource} 와 같은 규약.
 */
public class FakeLanguageModel implements LanguageModel {

    private final Deque<Object> scripted = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();
    private LlmResponse fallback = new LlmResponse("고정 응답", new LlmUsage(1, 1));

    public FakeLanguageModel respondWith(String text, int inputTokens, int outputTokens) {
        scripted.add(new LlmResponse(text, new LlmUsage(inputTokens, outputTokens)));
        return this;
    }

    /** 실패 주입 — 소비자가 예외 경로를 다루는지 보려면 필요하다. */
    public FakeLanguageModel failWith(LlmException failure) {
        scripted.add(failure);
        return this;
    }

    /** 스크립트가 떨어졌을 때 돌려줄 기본 응답. */
    public FakeLanguageModel withFallback(LlmResponse response) {
        this.fallback = response;
        return this;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public LlmResponse complete(AgentRunContext ctx, LlmRequest request) {
        calls.add(new Call(ctx, request));
        if (scripted.isEmpty()) {
            return fallback;
        }
        Object next = scripted.poll();
        if (next instanceof LlmException failure) {
            throw failure;
        }
        return (LlmResponse) next;
    }

    public record Call(AgentRunContext ctx, LlmRequest request) {
    }
}
