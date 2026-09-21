package com.ossagent.repository.adapter.in.web.dto;

import java.time.Instant;

public record ScanRequestedResponse(Long repositoryId, String status, Instant requestedAt) {
}
