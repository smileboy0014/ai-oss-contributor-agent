package com.ossagent.support.observability;

/**
 * 파이프라인 단계 — 계측용 어휘.
 *
 * <p>앞 넷은 스캔 파이프라인({@code ScanPipelineUseCase}), 뒤 다섯은 사람이 트리거하는 구현 루프와
 * PR 생성({@code ImplementCandidateUseCase} · {@code CreateDraftPrUseCase})이다.
 * 오랫동안 앞 넷만 있었다 — 「그 단계가 없어서 재지 않는다」였는데, #18~#23 으로 단계가 생긴 뒤에도
 * 계측이 따라가지 않아 대시보드가 구현·검증·리뷰를 0 으로 보였다.
 *
 * <p>⚠️ {@code repository.domain.ScanStage}(#14) 와 같은 네 값이지만 <b>따로 둔다.</b>
 *
 * <p>🔴 <b>원래 근거는 더 이상 성립하지 않는다.</b> 여기 적혀 있던 이유는 「그쪽이
 * {@code repository.application} 에 있어 {@code support} 가 import 하면 의존 방향이
 * 어긋난다」였는데, #26 이 그 enum 을 {@code domain} 으로 내렸다. 도메인 <b>값 타입</b>을
 * import 하는 것({@code LlmCallSite} 등)은 이 프로젝트가 허용한다 — 즉 지금은
 * <b>import 할 수 있다.</b>
 *
 * <p>그래도 따로 두는 이유는 하나 남는다 — {@code support} 는 <b>도메인 없는 공통</b>이고,
 * 계측 어휘가 {@code repository} 도메인의 것이 되면 다른 도메인의 단계를 셀 때
 * 남의 어휘를 빌려 쓰게 된다. 근거가 바뀌었다는 사실을 적어 두지 않으면 다음 사람이
 * <b>사라진 이유를 믿고</b> 판단한다.
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
