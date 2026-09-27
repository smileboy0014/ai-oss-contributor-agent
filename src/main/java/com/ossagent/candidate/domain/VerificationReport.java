package com.ossagent.candidate.domain;

import java.util.List;

/**
 * 검증 결과 <b>값</b> — #19 가 만들고 #18 의 오케스트레이션이 읽는다.
 *
 * <h2>🔴 실패는 예외가 아니라 값이다</h2>
 *
 * <p>{@code CodeSandbox} 가 「빌드 실패는 <b>결과</b>이지 예외가 아니다 — 그것이 이 제품의
 * 게이트가 작동한 모습이다」로 계약한 것과 같은 이유다. 검증 실패를 예외로 올리면
 * 호출자가 재시도 루프에서 <b>삼킨다.</b>
 *
 * <p>예외는 <b>검증 자체를 못 한 경우</b>에만 난다.
 *
 * @param passed   전체 판정
 * @param failures 실패 사유. 통과면 비어 있다. 🔴 <b>대상 저장소 텍스트가 섞이는 자리다</b> —
 *                 빌드 출력을 그대로 담으면 S-4 대상이 된다. 담는 쪽(#19)이 스크럽을 책임진다
 */
public record VerificationReport(boolean passed, List<String> failures) {

    public VerificationReport {
        failures = failures == null ? List.of() : List.copyOf(failures);
        // 🔴 「통과했는데 실패 사유가 있다」를 허용하지 않는다. 둘이 어긋나면 호출자가
        //    어느 쪽을 믿을지 정해야 하고, 그 판단이 호출자마다 갈린다
        if (passed && !failures.isEmpty()) {
            throw new IllegalArgumentException(
                    "통과 판정에 실패 사유가 함께 올 수 없다: " + failures);
        }
        // 🔴 반대 방향이 더 위험하다 — 사유 없는 실패는 #21 의 에러 분석이 헛돈다
        if (!passed && failures.isEmpty()) {
            throw new IllegalArgumentException("실패 판정에는 사유가 있어야 한다");
        }
    }

    /** ⚠ 이름이 {@code passed()} 가 아닌 이유 — record 접근자와 충돌한다. */
    public static VerificationReport success() {
        return new VerificationReport(true, List.of());
    }

    public static VerificationReport failure(String... reasons) {
        return new VerificationReport(false, List.of(reasons));
    }
}
