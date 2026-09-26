package com.ossagent.candidate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.candidate.adapter.out.persistence.ContributionCandidateRepository;
import com.ossagent.candidate.domain.CandidateNotFoundException;
import com.ossagent.candidate.domain.CandidateStatus;
import com.ossagent.candidate.domain.CandidateTransitionException;
import com.ossagent.candidate.domain.ContributionCandidate;
import com.ossagent.candidate.domain.IssueAnalysis;
import com.ossagent.candidate.domain.StatusTransition;
import com.ossagent.support.testing.AgentIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 승인 게이트 ① — 선정과 취소 (S-6).
 *
 * <p>🔴 <b>DB 를 다시 읽어 단언한다.</b> 반환값만 보면 {@code @Transactional} 이 빠져도
 * 통과한다 — 전이는 메모리 위의 엔티티에서 이미 일어났기 때문이다.
 * 이 게이트가 남기는 것은 「사람이 골랐다」는 <b>영속된 증거</b>라, 그 증거가 커밋되는지가
 * 검증 대상 그 자체다.
 */
@AgentIntegrationTest
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SelectCandidateUseCaseTest {

    @Autowired
    private SelectCandidateUseCase useCase;
    @Autowired
    private ContributionCandidateRepository candidates;
    @Autowired
    private Clock clock;

    /** 자기 패키지에서 만든다 — {@code candidate.domain} 의 픽스처는 패키지 가시성이다. */
    private static IssueAnalysis feasible() {
        return new IssueAnalysis("bug", IssueAnalysis.Difficulty.MEDIUM, true, 3, 80,
                true, false, new BigDecimal("0.90"), "고칠 만하다");
    }

    /**
     * ⚠️ 정리하지 않는다. {@code deleteAll()} 은 다른 테스트가 SQL 로 넣어 둔
     * {@code pull_request} 행이 후보를 참조하고 있어 실패한다(애그리거트 멤버라 {@code @OneToOne}).
     * 각 테스트는 <b>자기가 만든 id 만</b> 단언하므로 잔존 행에 영향받지 않는다.
     *
     * <p>⚠️ {@code issueId} 는 매번 새로 뽑는다 — 컬럼에 유니크 제약이 있다
     * (「스캔 재실행이 중복 후보를 만들지 않는다」의 DB 쪽 방어).
     */
    private Long analyzed() {
        ContributionCandidate candidate = ContributionCandidate.discover(System.nanoTime(), clock);
        candidate.startAnalysis(clock);
        candidate.completeAnalysis(feasible(), clock);
        return candidates.save(candidate).getId();
    }

    private Long selected() {
        Long id = analyzed();
        useCase.select(id);
        return id;
    }

    private CandidateStatus reread(Long id) {
        return candidates.findById(id).orElseThrow().getStatus();
    }

    @Test
    void 사람이_고르면_SELECTED_가_커밋된다_S6() {
        Long id = analyzed();

        StatusTransition transition = useCase.select(id);

        assertThat(transition)
                .isEqualTo(new StatusTransition(CandidateStatus.ANALYZED, CandidateStatus.SELECTED));
        assertThat(reread(id))
                .as("「사람이 골랐다」가 DB 에 남아야 승인 게이트 통과를 사후에 증명할 수 있다")
                .isEqualTo(CandidateStatus.SELECTED);
        assertThat(candidates.findById(id).orElseThrow().getSelectedAt())
                .as("selectedAt 이 그 증거다 — 이 UseCase 말고 채우는 곳이 없다")
                .isNotNull();
    }

    @Test
    void 선정은_다음_단계로_흘려보내지_않는다_S6() {
        Long id = analyzed();

        useCase.select(id);

        assertThat(reread(id))
                .as("선정은 트리아지(비용 0)이고 착수는 LLM + 샌드박스 30분이다. "
                        + "여기서 흘려보내면 Q-5 가 둘을 가른 이유가 사라진다")
                .isEqualTo(CandidateStatus.SELECTED);
        assertThat(candidates.findById(id).orElseThrow().getAttempt())
                .as("구현 시도가 시작되지 않았다")
                .isZero();
    }

    @Test
    void 같은_후보를_두_번_고를_수_없다_S6() {
        Long id = selected();

        assertThatThrownBy(() -> useCase.select(id))
                .as("🔴 멱등으로 만들면 「사람이 한 번 골랐다」를 사후에 셀 수 없다")
                .isInstanceOf(CandidateTransitionException.class);

        assertThat(reread(id)).isEqualTo(CandidateStatus.SELECTED);
    }

    @Test
    void 고르지_않은_후보는_취소할_수_없다_S6() {
        Long id = analyzed();

        assertThatThrownBy(() -> useCase.reject(id))
                .isInstanceOf(CandidateTransitionException.class);

        assertThat(reread(id))
                .as("거부는 상태를 남기지 않는다 — 롤백이 확인된다")
                .isEqualTo(CandidateStatus.ANALYZED);
    }

    @Test
    void 사람이_선택을_취소하면_REJECTED_가_커밋된다_Q5() {
        Long id = selected();

        StatusTransition transition = useCase.reject(id);

        assertThat(transition)
                .isEqualTo(new StatusTransition(CandidateStatus.SELECTED, CandidateStatus.REJECTED));
        assertThat(reread(id)).isEqualTo(CandidateStatus.REJECTED);
    }

    @Test
    void 취소는_되돌릴_수_없다_Q5() {
        Long id = selected();
        useCase.reject(id);

        assertThatThrownBy(() -> useCase.select(id))
                .as("REJECTED 는 종단이다. 다시 고르려면 재분석이 필요한 것이 의도다 — "
                        + "번복이 가벼우면 승인이 가벼워진다")
                .isInstanceOf(CandidateTransitionException.class);

        assertThat(reread(id)).isEqualTo(CandidateStatus.REJECTED);
    }

    @Test
    void 없는_후보는_조용히_넘어가지_않는다() {
        assertThatThrownBy(() -> useCase.select(404_404L))
                .isInstanceOf(CandidateNotFoundException.class);
        assertThatThrownBy(() -> useCase.reject(404_404L))
                .isInstanceOf(CandidateNotFoundException.class);
    }

    @Test
    void 식별자가_없으면_거부한다() {
        assertThatThrownBy(() -> useCase.select(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
