package com.ossagent.support.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link PipelineMetrics} 값 픽스처 — {@code testing-philosophy.md} 픽스처 규약.
 *
 * <p>🔴 <b>mock 을 쓰지 않는다.</b> {@code SimpleMeterRegistry} 는 대외 시스템이 아니라
 * 메모리 자료구조다. mock 으로 대체하면 <b>태그가 실제로 어떻게 등록되는지</b>를 검증할 수
 * 없고, 이 클래스의 존재 이유(S-4 — 태그에 외부 텍스트가 없다)가 통째로 사라진다.
 */
public final class PipelineMetricsFixtures {

    private PipelineMetricsFixtures() {
    }

    /** 계측이 실제로 등록되는 인스턴스 — 레지스트리는 버린다. */
    public static PipelineMetrics discarding() {
        return new PipelineMetrics(new SimpleMeterRegistry());
    }
}
