package com.ossagent.issue.domain;

import java.util.List;

/**
 * 라벨로 기여 우선순위를 매긴다 — #9 FR-4.
 *
 * <h2>🔴 배제 규칙이 아니다</h2>
 *
 * <p>이슈 문구는 「우선 <b>탐색</b> 라벨」이다. 「이 라벨이 없으면 배제」가 아니다.
 * 대부분의 이슈에 이 라벨이 없어서, 배제로 쓰면 Phase 1 대상({@code spring-kafka}
 * 단일)이 거의 다 떨어진다.
 *
 * <p>점수는 {@code issue.filter_priority} 에 <b>영속된다.</b> 반환값으로만 두면
 * 트랜잭션이 끝나는 순간 사라지고, #11 이 분석 순서를 정할 때 SQL 로 정렬할 수 없다.
 *
 * <p>⚠ 점수는 <b>합산하지 않는다.</b> 가장 높은 것 하나를 쓴다. 합산하면 라벨을 많이
 * 붙이는 저장소가 구조적으로 앞서고, 점수의 뜻이 「얼마나 쉬운가」에서 「라벨이 몇 개냐」로
 * 바뀐다.
 *
 * <p>⚠ {@code SMALLINT} 에 들어간다. 값을 늘릴 때 상한을 넘기지 않는다.
 */
public final class LabelPriority {

    /**
     * 위에서부터 먼저 걸리는 것이 점수다.
     *
     * <p>{@code good first issue} 가 가장 높은 이유 — 메인테이너가 <b>입문자에게
     * 열어 둔 것</b>이라 기여 규약·리뷰 부담이 가장 낮다. {@code documentation} 이
     * 가장 낮은 이유는 난이도가 아니라, 코드 변경이 없어 이 제품의 검증 게이트
     * (빌드·테스트)가 거의 작동하지 않기 때문이다.
     */
    private static final List<Tier> TIERS = List.of(
            new Tier("good first issue", 100),
            new Tier("help wanted", 80),
            new Tier("bug", 60),
            new Tier("enhancement", 40),
            new Tier("documentation", 20));

    /** 우선순위 라벨이 하나도 없을 때. <b>배제가 아니다</b> — 그냥 뒤로 간다. */
    public static final int NONE = 0;

    private LabelPriority() {
    }

    public static int scoreOf(Issue issue) {
        for (Tier tier : TIERS) {
            if (issue.hasLabel(tier.label())) {
                return tier.score();
            }
        }
        return NONE;
    }

    private record Tier(String label, int score) {
    }
}
