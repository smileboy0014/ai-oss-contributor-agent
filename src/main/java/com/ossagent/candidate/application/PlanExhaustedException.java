package com.ossagent.candidate.application;

import java.util.List;

/**
 * 구현 계획을 상한 안에 세우지 못했다 — 이슈 #16 · S-6.
 *
 * <p>🔴 <b>이 예외가 던져진 시점에 후보는 이미 {@code FAILED} 다.</b> 「예외를 잡아서
 * 후보를 실패시켜 주세요」가 아니다 — 호출자가 그것을 잊으면 후보가 박히고,
 * {@code safety-boundaries.md} S-6 이 막으려는 상황이 그대로 생긴다.
 * <b>상태 전이는 던지는 쪽이 끝낸다.</b>
 *
 * <p>호출자가 이 예외로 할 일은 <b>자기 흐름을 멈추는 것</b>뿐이다.
 *
 * <p>⚠️ 위반 사유를 들고 있지만 <b>메시지에 펼치지 않는다.</b> 사유에는 대상 저장소 경로가
 * 섞여 있고, 예외 메시지는 로그·API 응답 어디로든 흘러간다 — {@code logging.md}.
 */
public class PlanExhaustedException extends RuntimeException {

    private final transient List<String> violations;

    public PlanExhaustedException(Long candidateId, int maxAttempts, List<String> violations) {
        super("구현 계획을 %d회 안에 세우지 못했습니다 candidateId=%s violations=%d"
                .formatted(maxAttempts, candidateId,
                        violations == null ? 0 : violations.size()));
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /** 마지막 거부 사유. 사람이 보는 화면에서 쓴다 — 로그 포맷 문자열로 쓰지 않는다 */
    public List<String> violations() {
        return violations;
    }
}
