package com.ossagent.candidate.adapter.in.web.dto;

import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.StatusTransition;

/** 착수 접수 — 202. 전이는 이 요청 안에서 끝났고 루프는 백그라운드다 (#106). */
public record ImplementAcceptedResponse(Long candidateId, CandidateStatus from, CandidateStatus to,
        String status, String statusUrl) {

    public static ImplementAcceptedResponse of(Long candidateId, StatusTransition transition) {
        return new ImplementAcceptedResponse(candidateId, transition.from(), transition.to(),
                "IMPLEMENT_ACCEPTED", "/api/candidates/%d/implement".formatted(candidateId));
    }
}
