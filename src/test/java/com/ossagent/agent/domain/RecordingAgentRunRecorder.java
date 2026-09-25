package com.ossagent.agent.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 기록 호출을 그대로 모아 두는 대역. 「무엇이 장부에 남았는가」를 검증한다. */
public class RecordingAgentRunRecorder implements AgentRunRecorder {

    private final AtomicLong ids = new AtomicLong();
    private final List<String> events = new ArrayList<>();

    public List<String> events() {
        return List.copyOf(events);
    }

    @Override
    public Long started(AgentRunContext ctx) {
        long id = ids.incrementAndGet();
        events.add("started:" + ctx.callSite() + ":attempt=" + ctx.attempt());
        return id;
    }

    @Override
    public void succeeded(Long runId, LlmUsage usage) {
        events.add("succeeded:" + runId + ":in=" + usage.inputTokens() + ":out=" + usage.outputTokens());
    }

    @Override
    public void failed(Long runId, LlmFailureReason reason, LlmUsage usage) {
        events.add("failed:" + runId + ":" + reason
                + (usage == null ? ":usage=unknown" : ":out=" + usage.outputTokens()));
    }
}
