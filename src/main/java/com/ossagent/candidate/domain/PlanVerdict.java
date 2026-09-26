package com.ossagent.candidate.domain;

import java.util.List;

/**
 * 계획 검증의 결과 — 이슈 #16 FR-2~FR-4.
 *
 * <h2>🔴 예외가 아니라 값인 이유</h2>
 * 「모델이 틀렸다」는 <b>정상 상황</b>이다. 예외로 던지면 위반 사유가 스택트레이스로
 * 흩어지고, 재생성이 <b>같은 실수를 반복</b>한다. 값으로 받아야 사유를 프롬프트에
 * 되먹일 수 있다 — 그것이 「상한 내 재생성」(FR-5)이 의미를 갖는 조건이다.
 *
 * <p>⚠️ 사유가 <b>사람이 읽을 문장</b>이자 <b>모델에게 줄 지시</b>다. 「검증 실패」 같은
 * 뭉뚱그린 문구를 넣지 않는다 — 무엇을 고쳐야 하는지가 그 문장에 없으면 재생성이
 * 동전 던지기가 된다.
 *
 * @param violations 위반 사유. <b>비어 있으면 통과</b>다
 */
public record PlanVerdict(List<String> violations) {

    public PlanVerdict {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static PlanVerdict passed() {
        return new PlanVerdict(List.of());
    }

    public static PlanVerdict rejected(List<String> violations) {
        if (violations == null || violations.isEmpty()) {
            // 🔴 「거부인데 사유가 없다」를 허용하면 재생성 프롬프트가 빈다.
            //    그러면 모델이 무엇을 고쳐야 할지 모른 채 같은 계획을 다시 낸다
            throw new IllegalArgumentException("거부에는 사유가 있어야 한다 — 재생성이 그것을 먹는다");
        }
        return new PlanVerdict(violations);
    }

    public boolean isPassed() {
        return violations.isEmpty();
    }

    /**
     * 재생성 프롬프트에 실을 형태.
     *
     * <p>모델에게 <b>무엇이 왜 거부됐는지</b>를 그대로 준다. 이 문자열이 다음 호출의
     * 입력이므로, 여기가 비면 재생성이 무의미하다.
     */
    public String asFeedback() {
        StringBuilder feedback = new StringBuilder("직전 계획이 거부됐다. 아래를 전부 고쳐서 다시 낸다.\n");
        for (String violation : violations) {
            feedback.append("- ").append(violation).append('\n');
        }
        return feedback.toString();
    }

    @Override
    public String toString() {
        return "PlanVerdict[passed=%s, violations=%d]".formatted(isPassed(), violations.size());
    }
}
