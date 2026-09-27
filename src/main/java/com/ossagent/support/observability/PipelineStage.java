package com.ossagent.support.observability;

/**
 * 파이프라인 단계 — 계측용 어휘.
 *
 * <p>앞 넷은 스캔 파이프라인({@code ScanPipelineUseCase}), 뒤 넷은 사람이 트리거하는 구현 루프와
 * PR 생성({@code ImplementCandidateUseCase} · {@code CreateDraftPrUseCase})이다.
 * 오랫동안 앞 넷만 있었다 — 「그 단계가 없어서 재지 않는다」였는데, #18~#23 으로 단계가 생긴 뒤에도
 * 계측이 따라가지 않아 대시보드가 구현·검증·리뷰를 0 으로 보였다.
 *
 * <p>⚠️ {@code ScanExecutionState.Stage}(#14) 와 앞 네 값이 같지만 <b>따로 둔다.</b>
 * 그쪽은 {@code repository.application} 에 있고, {@code support} 가 남의 application 을
 * import 하면 의존 방향이 어긋난다({@code support} 는 도메인 없는 공통이다).
 * 도메인 <b>값 타입</b>을 import 하는 것({@code LlmCallSite} 등)과는 사정이 다르다.
 *
 * <p>매핑은 호출부({@code ScanPipelineUseCase})가 한다 — 한 줄이고, 그 대가로
 * {@code support} 가 어느 도메인의 내부 구조에도 묶이지 않는다.
 */
public enum PipelineStage {
    POLICY,
    SCAN,
    FILTER,
    ANALYZE,
    /** 착수 게이트 뒤, 전이 앞 — 구현 계획 수립(#16). 루프 밖이라 후보당 1회다. */
    PLAN,
    /** 구현 루프의 세 단계 — {@code attempt} 마다 한 번씩 찍힌다. */
    CODE,
    VERIFY,
    REVIEW,
    /** 세 번째 승인 게이트 뒤 — Fork push + Draft PR 생성을 한 단계로 잰다. */
    PULL_REQUEST
}
