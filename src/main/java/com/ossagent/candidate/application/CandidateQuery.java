package com.ossagent.candidate.application;

import com.ossagent.candidate.domain.CandidateStatus;
import java.math.BigDecimal;

/**
 * 목록 조회 조건. <b>전부 선택적</b>이고 {@code null} 은 「그 축으로 거르지 않는다」는 뜻이다.
 *
 * <p>⚠️ <b>저장소 필터가 없는 것은 의도다.</b> {@code contribution_candidate} 에 {@code repository_id}
 * 가 없어 {@code issue} 를 경유해야 하는데, 이슈는 저장소당 수천 개라 {@code issueId IN (...)} 이
 * <b>모집단으로 쿼리를 만드는 꼴</b>이 된다. 맞는 답은 {@code repository_id} 비정규화이고
 * 그것을 채우는 것은 #11 이다 — {@code docs/plans/PLAN-13.md} §2.
 *
 * <p>🔴 <b>「일단 받아 두고 무시」를 하지 않는다.</b> 파라미터를 받아 놓고 거르지 않으면
 * 호출자는 걸러진 줄 안다. 지원하지 않는 축은 <b>받지 않는 편이 정직하다.</b>
 *
 * @param status        상태. {@code null} 이면 전체
 * @param difficulty    난이도. DB 가 {@code VARCHAR} 에 CHECK 가 없어 <b>자유 문자열</b>이다 —
 *                      없는 값은 400 이 아니라 빈 목록이다
 * @param minConfidence 이 값 <b>이상</b>. {@code null} 이면 전체
 */
public record CandidateQuery(CandidateStatus status, String difficulty, BigDecimal minConfidence) {

    /** 기본 페이지 크기. */
    public static final int DEFAULT_SIZE = 20;

    /**
     * 페이지 크기 상한.
     *
     * <p>TEXT 를 싣지 않아도 대량 응답은 메모리와 응답 시간을 먹는다. 상한 없는 {@code size} 는
     * 사실상 전체 조회다.
     */
    public static final int MAX_SIZE = 100;

    /** 조건 없는 조회 — 전체. */
    public static CandidateQuery all() {
        return new CandidateQuery(null, null, null);
    }
}
