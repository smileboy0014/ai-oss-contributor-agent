package com.ossagent.candidate.adapter.in.web.dto;

import com.ossagent.candidate.application.ImplementationRegistry.ImplementationProgress;
import java.time.Instant;

/**
 * 착수 진행 조회 (#106). ⚠️ 프로세스 메모리다 — 재기동하면 {@code IDLE} 로 돌아간다.
 * 후보의 <b>상태</b>는 {@code GET /api/candidates/{id}} 가 정본이다.
 */
public record ImplementationProgressResponse(Long candidateId, String phase, Instant startedAt,
        Instant updatedAt, String message) {

    public static ImplementationProgressResponse from(ImplementationProgress progress) {
        return new ImplementationProgressResponse(progress.candidateId(), progress.phase().name(),
                progress.startedAt(), progress.updatedAt(), progress.message());
    }
}
