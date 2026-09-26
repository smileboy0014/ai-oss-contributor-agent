package com.ossagent.candidate.adapter.in.web.dto;

import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.StatusTransition;

/**
 * 승인 게이트 통과 결과 — #24 · S-6.
 *
 * <p><b>전이의 양끝을 그대로 돌려준다.</b> {@code to} 만 주면 호출자가 「원래 뭐였는지」를
 * 알 수 없어 재요청인지 첫 요청인지 구분하지 못한다. 멱등이 아닌 행위라
 * (두 번째 호출은 409) 그 구분이 화면에 필요하다.
 *
 * <p>⚠️ {@code terminal} 을 <b>계산해서 싣는다.</b> 「{@code REJECTED} 가 종단인가」를
 * 클라이언트가 상태 이름으로 판정하게 두면, 종단 집합이 바뀔 때 화면이 따라오지 못한다.
 * 판정의 정본은 {@link CandidateStatus#isTerminal()} 하나다.
 */
public record SelectionResponse(
        Long candidateId,
        CandidateStatus from,
        CandidateStatus to,
        boolean terminal) {

    public static SelectionResponse from(Long candidateId, StatusTransition transition) {
        return new SelectionResponse(
                candidateId, transition.from(), transition.to(), transition.to().isTerminal());
    }
}
