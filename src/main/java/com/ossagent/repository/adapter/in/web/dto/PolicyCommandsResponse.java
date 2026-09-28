package com.ossagent.repository.adapter.in.web.dto;

import java.time.Instant;

/** 명령 수동 설정 결과 — 명령 자체는 되돌려주지 않는다(입력을 그대로 에코하지 않는다). */
public record PolicyCommandsResponse(Long repositoryId, Instant overriddenAt) {
}
