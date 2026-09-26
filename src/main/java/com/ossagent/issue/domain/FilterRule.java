package com.ossagent.issue.domain;

import java.util.List;

/**
 * 규칙 하나 — #9 FR-1.
 *
 * <h2>🔴 대외 호출을 하지 않는다</h2>
 *
 * <p>이 단계의 존재 이유가 「LLM 에 넣기 전에 <b>싸게</b> 거른다」이다. 규칙이 GitHub 을
 * 부르는 순간 그 목적이 무너진다 — 아끼려던 것보다 더 쓰게 된다. 입력은 <b>이미 저장된
 * {@link Issue} 행</b> 하나뿐이고, 판정은 순수 함수여야 한다.
 *
 * <p>같은 입력에 항상 같은 사유가 나와야 한다. 시각·난수·외부 상태에 의존하는 규칙은
 * 재현이 안 돼 사유 분포를 신뢰할 수 없다.
 *
 * <h2>⚠ 입력과 무관하게 같은 값을 내는 규칙은 규칙이 아니다</h2>
 *
 * <p>「활성 PR 존재」를 여기 두지 않은 이유다. 확인 수단이 없어 늘 {@code UNDECIDED} 를
 * 내면 집계에서 {@link FilterOutcome#PASSED} 가 <b>도달 불가능한 죽은 값</b>이 되고,
 * 하류가 {@code UNDECIDED} 를 통과로 뭉갤 수밖에 없다. 이슈 단위 판정이 아니라
 * <b>단계 전체의 공백</b>이므로 행마다 기록하지 않는다 — #11 입구와 #23 의 몫이다.
 */
public interface FilterRule {

    /**
     * 이 규칙에 걸린 사유. 걸리지 않으면 <b>빈 목록</b>이다.
     *
     * <p>{@code null} 을 돌려주지 않는다 — 「판정 없음」과 「통과」가 섞이면 집계가 흔들린다.
     */
    List<FilterReason> evaluate(Issue issue);
}
