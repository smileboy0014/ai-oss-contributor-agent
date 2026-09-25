package com.ossagent.candidate.domain;

import static com.ossagent.candidate.domain.CandidateStatus.ANALYZED;
import static com.ossagent.candidate.domain.CandidateStatus.ANALYZING;
import static com.ossagent.candidate.domain.CandidateStatus.DISCOVERED;
import static com.ossagent.candidate.domain.CandidateStatus.FAILED;
import static com.ossagent.candidate.domain.CandidateStatus.IMPLEMENTING;
import static com.ossagent.candidate.domain.CandidateStatus.PR_CREATED;
import static com.ossagent.candidate.domain.CandidateStatus.READY_FOR_PR;
import static com.ossagent.candidate.domain.CandidateStatus.REJECTED;
import static com.ossagent.candidate.domain.CandidateStatus.REVIEWING;
import static com.ossagent.candidate.domain.CandidateStatus.SELECTED;
import static com.ossagent.candidate.domain.CandidateStatus.TESTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 전이 규칙 — <b>전수 대조</b>.
 *
 * <p>🔴 「허용된 것이 되는가」만 보면 <b>허용되지 않아야 할 것이 되는지</b>는 검증되지 않는다.
 * S-6 는 후자가 깨질 때 무너지므로 11×11 = 121 조합을 전부 본다.
 *
 * <p>아래 표가 {@code .claude/codemaps/domain.md} 전이 표의 사본이다. <b>둘이 갈라지면
 * 이 테스트가 깨지는 것이 의도다</b> — 문서와 코드가 조용히 어긋나지 않는다.
 */
class CandidateStatusTest {

    /** codemaps/domain.md 전이 표의 사본. 여기에 없는 조합은 전부 금지다. */
    private static final Map<CandidateStatus, Set<CandidateStatus>> EXPECTED =
            new EnumMap<>(CandidateStatus.class);

    static {
        EXPECTED.put(DISCOVERED, Set.of(ANALYZING));
        EXPECTED.put(ANALYZING, Set.of(ANALYZED, FAILED));
        EXPECTED.put(ANALYZED, Set.of(SELECTED, REJECTED));
        EXPECTED.put(SELECTED, Set.of(IMPLEMENTING, REJECTED));
        EXPECTED.put(IMPLEMENTING, Set.of(TESTING, FAILED));
        EXPECTED.put(TESTING, Set.of(REVIEWING, IMPLEMENTING, FAILED));
        EXPECTED.put(REVIEWING, Set.of(READY_FOR_PR, IMPLEMENTING, FAILED));
        EXPECTED.put(READY_FOR_PR, Set.of(PR_CREATED));
        EXPECTED.put(PR_CREATED, Set.of());
        EXPECTED.put(REJECTED, Set.of());
        EXPECTED.put(FAILED, Set.of());
    }

    @Test
    @DisplayName("전이 표에 없는 조합은 전부 거부한다 (11×11 전수)")
    void 허용되지_않은_전이는_전부_거부한다_S6() {
        for (CandidateStatus from : CandidateStatus.values()) {
            for (CandidateStatus to : CandidateStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);

                assertThat(from.canTransitionTo(to))
                        .as("%s → %s 는 %s 여야 한다 — codemaps/domain.md 전이 표", from, to,
                                expected ? "허용" : "금지")
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("종단 상태에서는 어떤 전이도 일어나지 않는다")
    void 종단_상태에서는_어떤_전이도_일어나지_않는다_S6() {
        for (CandidateStatus terminal : Set.of(PR_CREATED, REJECTED, FAILED)) {
            assertThat(terminal.isTerminal())
                    .as("%s 는 종단이어야 한다", terminal)
                    .isTrue();

            for (CandidateStatus to : CandidateStatus.values()) {
                assertThat(terminal.canTransitionTo(to))
                        .as("%s 는 종단인데 %s 로 나가는 길이 열려 있다 — PR_CREATED 후보가 다시 "
                                + "구현 루프에 들어가 같은 PR 을 덮어쓴다 (불변식 ①)", terminal, to)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("종단이 아닌 상태는 나가는 길이 하나 이상 있다")
    void 종단이_아니면_빠져나갈_길이_있다_S6() {
        for (CandidateStatus status : CandidateStatus.values()) {
            if (status.isTerminal()) {
                continue;
            }
            assertThat(status.allowedNext())
                    .as("%s 는 종단이 아닌데 나가는 길이 없다 — 후보가 여기 영구히 박힌다. "
                            + "ANALYZING 이 실제로 그랬다 (Q-6)", status)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("자기 자신으로 가는 전이는 없다")
    void 자기_자신으로는_전이하지_않는다() {
        for (CandidateStatus status : CandidateStatus.values()) {
            assertThat(status.canTransitionTo(status))
                    .as("%s → %s 자기 전이가 허용되면 같은 전이의 중복 호출이 조용히 통과한다", status, status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("분석 실패는 FAILED 로 갈 수 있다 — Q-6")
    void 분석_실패는_종단으로_간다_S6() {
        assertThat(ANALYZING.canTransitionTo(FAILED))
                .as("ANALYZE·PLAN 실패는 즉시 FAILED 다 (Q-6). 없으면 후보가 ANALYZING 에 "
                        + "영구히 박혀 사람이 볼 신호가 발생하지 않는다")
                .isTrue();
    }

    @Test
    @DisplayName("허용 목적지 집합은 수정할 수 없다")
    void 전이_규칙을_바깥에서_늘릴_수_없다_S6() {
        assertThatThrownBy(() -> PR_CREATED.allowedNext().add(IMPLEMENTING))
                .as("호출자가 규칙을 늘릴 수 있으면 종단 보장이 런타임에 무너진다")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null 목적지는 거부한다")
    void null_목적지는_허용하지_않는다() {
        assertThat(DISCOVERED.canTransitionTo(null)).isFalse();
    }
}
