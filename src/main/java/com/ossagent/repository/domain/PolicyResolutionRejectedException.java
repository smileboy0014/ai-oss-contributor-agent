package com.ossagent.repository.domain;

/**
 * 보류 해소가 거부됐다 — Q-8 · #24.
 *
 * <p>「해소할 수 없는 상태」다 — 이미 금지이거나(뒤집으면 FR-2 가 호출 한 번으로 풀린다),
 * 이미 허용인데 다시 허용하려는 경우다.
 *
 * <p>🔴 <b>정책 행이 <i>없는</i> 경우는 여기가 아니다.</b> 그것은 「충돌」이 아니라
 * 「없음」이고 {@link RepositoryPolicyNotFoundException} 이 맡는다 —
 * 행이 없는데 해소해 주면 <b>「읽지 않고 허용」</b> 이 되어 S-5 가 정면으로 뚫린다.
 *
 * <p>HTTP 매핑은 {@code support/web} 이 한다 — 도메인에 {@code @ResponseStatus} 를 달면
 * 같은 예외를 스케줄러가 던질 때 의미가 없어진다(규율 ④).
 */
public class PolicyResolutionRejectedException extends RuntimeException {

    public PolicyResolutionRejectedException(String message) {
        super(message);
    }
}
