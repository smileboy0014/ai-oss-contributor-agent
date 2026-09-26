package com.ossagent.repository.adapter.in.web.dto;

import com.ossagent.repository.application.ScanExecutionState;
import com.ossagent.repository.application.ScanPipelineResult;
import java.time.Instant;

/**
 * 진행 조회 본문 — #14 FR-3.
 *
 * <p>🔴 <b>실패 사유에 예외 원문이 없다</b> (S-4). {@code failureType} 은 예외 클래스
 * 이름이고 {@code failureStage} 는 우리 단계 어휘다. 예외 메시지에는 요청 URL·모델 응답·
 * 대상 저장소 텍스트가 실려 올 수 있고, 이 응답은 HTTP 로 나간다.
 *
 * @param hasMore 🔴 「한 번 돌렸다」와 「다 돌았다」는 다르다. 상한에 걸려 남는 것이 정상이고,
 *                <b>자동으로 이어 달리지 않으므로</b> 사람이 이 값을 볼 수 있어야 한다
 */
public record ScanProgressResponse(
        Long repositoryId,
        String phase,
        Instant startedAt,
        Instant finishedAt,
        Integer issuesSaved,
        Integer issuesJudged,
        Integer candidatesAnalyzed,
        Integer candidatesRejected,
        Integer candidatesFailed,
        Boolean hasMore,
        Instant delayedUntil,
        String skipReason,
        String failureStage,
        String failureType) {

    public static ScanProgressResponse from(ScanExecutionState state) {
        ScanPipelineResult result = state.lastResult();
        return new ScanProgressResponse(
                state.repositoryId(),
                state.phase().name(),
                state.startedAt(),
                state.finishedAt(),
                result == null ? null : result.issuesSaved(),
                result == null ? null : result.issuesJudged(),
                result == null ? null : result.candidatesAnalyzed(),
                result == null ? null : result.candidatesRejected(),
                result == null ? null : result.candidatesFailed(),
                result == null ? null : result.hasMore(),
                result == null ? null : result.delayedUntil(),
                result == null || result.skipReason() == null ? null : result.skipReason().name(),
                state.failureStage() == null ? null : state.failureStage().name(),
                state.failureType());
    }
}
