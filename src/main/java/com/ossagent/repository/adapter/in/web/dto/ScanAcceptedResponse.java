package com.ossagent.repository.adapter.in.web.dto;

import java.time.Instant;

/**
 * {@code 202 Accepted} 본문 — #14 FR-3.
 *
 * <p>🔴 <b>「요청을 받았다」이지 「끝났다」가 아니다.</b> 스캔은 GitHub 페이지 수십 개와
 * LLM 호출 N회라 분 단위다. 동기로 돌려주면 API 스레드가 그동안 잡힌다(NFR-1).
 *
 * @param statusUrl 진행 조회 경로. <b>202 의 짝</b>이다 — 「나중에 보라」고만 하고
 *                  어디서 보는지 안 주면 호출자가 할 수 있는 것이 없다
 */
public record ScanAcceptedResponse(Long repositoryId, String status, Instant acceptedAt,
        String statusUrl) {

    public static ScanAcceptedResponse of(Long repositoryId, Instant acceptedAt) {
        return new ScanAcceptedResponse(repositoryId, "SCAN_ACCEPTED", acceptedAt,
                "/api/repositories/%d/scan".formatted(repositoryId));
    }
}
