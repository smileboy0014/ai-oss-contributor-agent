package com.ossagent.repository.domain;

/**
 * 수집을 시작하지 않는 사유 — 🔴 <b>실패가 아니다</b>.
 *
 * <h2>⚠️ 알려진 한계 — {@code POLICY_UNAVAILABLE} 이 두 가지를 합치고 있다</h2>
 *
 * <p>{@code AnalyzeRepositoryPolicyUseCase.analyze()} 는 <b>일시적 실패</b>와
 * <b>보관된 저장소</b>를 둘 다 {@code Optional.empty()} 로 돌려준다. 하나는 일시이고
 * 하나는 <b>영구</b>인데 바깥에서 가를 수단이 없다 — {@code archived} 는 GitHub 응답에만
 * 있고 {@code oss_repository} 에 저장되지 않는다.
 *
 * <p>그래서 보관된 저장소를 <b>매 주기 메타데이터 호출로 두드리게 된다.</b>
 * 대상이 한 곳인 Phase 1 에서는 주기당 호출 1회라 감당되지만, 저장소가 늘면 낭비가 는다.
 *
 * <p>🔴 <b>{@code ARCHIVED} 상수를 만들어 두지 않는다.</b> 만들어도 이 코드가 그것을
 * 내보낼 방법이 없어 <b>도달 불가능한 어휘</b>가 되고, 다음 사람이 「구분되고 있다」고
 * 오해한다. 해소하려면 {@code analyze()} 가 사유를 실은 타입을 돌려줘야 한다 —
 * #7 의 계약 변경이다.
 *
 * <h2>왜 {@code domain} 에 있나 — 원래 {@code ScanTarget} 안에 중첩돼 있었다 (#26)</h2>
 *
 * <p>{@link ScanPhase} 와 같은 이유다. #26 이 실행 상태를 DB 로 옮기면서 엔티티
 * ({@code ScanExecution})가 이 사유를 저장해야 했고, domain 이 application 을 import
 * 하면 의존 방향이 뒤집힌다.
 *
 * <p>⚠️ <b>엔티티가 이것을 문자열로 들지 않게 하려고 내렸다.</b> 문자열로 들면 저장과
 * 복원이 {@code valueOf} 가 되어, 어휘가 바뀌는 날 <b>읽는 쪽에서</b> 터진다.
 */
public enum ScanSkipReason {

    /**
     * 규약을 읽지 못했다 — 다음 주기가 재시도한다.
     * ⚠️ <b>보관된 저장소도 여기로 들어온다</b>(위 한계).
     */
    POLICY_UNAVAILABLE,

    /** 규약 판정이 서지 않았다(보류) — 🔴 사람이 푼다. 자동으로 풀리지 않는다 (Q-8 확정 ②) */
    POLICY_UNDETERMINED,

    /** AI 기여를 금지한다 */
    CONTRIBUTION_FORBIDDEN,

    /** 레이트리밋 — 🔴 실패가 아니라 <b>지연</b>이다 */
    RATE_LIMITED
}
