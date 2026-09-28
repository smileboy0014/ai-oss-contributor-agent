package com.ossagent.repository.adapter.in.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 저장소별 스캔 주기 — #108. {@code minutes} 가 {@code null} 이면 기본 주기({@code scan.default-interval})로 되돌린다.
 * 상한 7일 — 그 이상은 「보지 않는다」에 가깝고, 그것은 {@code enabled=false} 로 표현한다.
 */
public record ScanIntervalRequest(@Min(1) @Max(10080) Integer minutes) {
}
