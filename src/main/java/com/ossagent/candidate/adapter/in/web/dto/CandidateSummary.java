package com.ossagent.candidate.adapter.in.web.dto;

import com.ossagent.candidate.domain.CandidateStatus;

public record CandidateSummary(Long id, Long issueId, CandidateStatus status, double confidence) {
}
