package com.ossagent.candidate.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 후보 상태머신의 불변식 — <b>안전 경계 경로는 커버리지 100% 대상</b>이다.
 *
 * <p>모든 테스트는 {@link ContributionCandidate#discover} 에서 시작해 <b>합법 전이만으로</b>
 * 목표 상태에 도달한다. 리플렉션으로 상태를 주입하지 않는다 — 그렇게 하면
 * 「거기까지 가는 경로 자체」가 검증되지 않는다.
 */
class ContributionCandidateTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");
    private static final int MAX_ATTEMPTS = 3;

    /** 시각을 고정한다 — `Instant.now()` 직접 호출 금지(architecture.md §4). */
    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static Clock clock() {
        return at(T0);
    }

    private static ContributionCandidate discovered() {
        return ContributionCandidate.discover(42L, clock());
    }

    private static ContributionCandidate analyzed() {
        ContributionCandidate candidate = discovered();
        candidate.startAnalysis(clock());
        candidate.completeAnalysis(clock());
        return candidate;
    }

    private static ContributionCandidate selected() {
        ContributionCandidate candidate = analyzed();
        candidate.selectByHuman(clock());
        return candidate;
    }

    private static ContributionCandidate implementing() {
        ContributionCandidate candidate = selected();
        candidate.startImplementing(MAX_ATTEMPTS, clock());
        return candidate;
    }

    // ─────────────────────────────── 생성 ───────────────────────────────

    @Test
    @DisplayName("후보는 DISCOVERED · attempt 0 · 미선택으로 시작한다")
    void 후보는_DISCOVERED_로_생긴다() {
        ContributionCandidate candidate = discovered();

        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.DISCOVERED);
        assertThat(candidate.getAttempt())
                .as("NOT NULL 컬럼이다. 초기화 주체가 없으면 insert 가 제약 위반으로 죽는다")
                .isZero();
        assertThat(candidate.isNotSelectedByHuman())
                .as("생성 시점에 사람이 고른 적이 없다 — S-6")
                .isTrue();
        assertThat(candidate.getCreatedAt()).isEqualTo(T0);
        assertThat(candidate.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("issueId 없이는 후보를 만들 수 없다")
    void issueId_없이는_만들_수_없다() {
        assertThatThrownBy(() -> ContributionCandidate.discover(null, clock()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────── 불변식 ① 종단 ───────────────────────

    @Test
    @DisplayName("종단 상태에서는 어떤 전이도 일어나지 않는다")
    void 종단_상태에서는_어떤_전이도_일어나지_않는다_S6() {
        ContributionCandidate prCreated = implementing();
        prCreated.startTesting(clock());
        prCreated.startReview(clock());
        prCreated.markReadyForPr(clock());
        prCreated.markPrCreated(clock());

        assertThat(prCreated.isTerminal()).isTrue();
        assertThatThrownBy(() -> prCreated.startImplementing(MAX_ATTEMPTS, clock()))
                .as("PR_CREATED 후보가 다시 구현 루프에 들어가면 같은 PR 을 덮어쓴다 — 불변식 ①")
                .isInstanceOf(CandidateTransitionException.class);
        assertThatThrownBy(() -> prCreated.fail(clock()))
                .isInstanceOf(CandidateTransitionException.class);
    }

    // ─────────────────────── 불변식 ② 사람 승인 ───────────────────────

    @Test
    @DisplayName("SELECTED 는 사람 행위로만 도달하고 selectedAt 이 그 증거다")
    void SELECTED_는_사람_행위로만_도달한다_S6() {
        Instant selectedTime = T0.plus(Duration.ofHours(3));
        ContributionCandidate candidate = analyzed();
        assertThat(candidate.isNotSelectedByHuman()).isTrue();

        candidate.selectByHuman(at(selectedTime));

        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.SELECTED);
        assertThat(candidate.getSelectedAt())
                .as("「사람이 최종 승인한다」는 제품 정의의 유일한 데이터 증거다 — S-6")
                .isEqualTo(selectedTime);
        assertThat(candidate.isNotSelectedByHuman()).isFalse();
    }

    @Test
    @DisplayName("selectByHuman 말고는 selectedAt 을 채우는 경로가 없다")
    void 다른_전이는_selectedAt_을_채우지_않는다_S6() {
        ContributionCandidate candidate = discovered();

        candidate.startAnalysis(clock());
        assertThat(candidate.isNotSelectedByHuman()).isTrue();

        candidate.completeAnalysis(clock());
        assertThat(candidate.isNotSelectedByHuman())
                .as("스케줄러가 흘려보낸 후보가 「사람이 골랐다」로 기록되면 승인 지점이 무너진다")
                .isTrue();
    }

    @Test
    @DisplayName("사람은 선택을 취소할 수 있고, 취소는 종단이다")
    void 사람은_선택을_취소할_수_있다_S6() {
        ContributionCandidate candidate = selected();

        StatusTransition transition = candidate.cancelSelection(clock());

        assertThat(transition).isEqualTo(
                new StatusTransition(CandidateStatus.SELECTED, CandidateStatus.REJECTED));
        assertThat(candidate.isTerminal())
                .as("REJECTED 는 종단이다. 다시 고르려면 재분석이 필요한 것은 의도다 — "
                        + "번복이 가벼우면 승인이 가벼워진다 (Q-5)")
                .isTrue();
    }

    @Test
    @DisplayName("사람이 고르지 않으면 구현 단계로 갈 수 없다 — 게이트가 전이표와 독립적이다")
    void 사람이_고르지_않으면_구현할_수_없다_S6() {
        ContributionCandidate candidate = analyzed();

        assertThatThrownBy(() -> candidate.startImplementing(MAX_ATTEMPTS, clock()))
                .as("selectedAt 이 「NULL 이면 구현 단계로 갈 수 없다」고 문서가 단언한다. "
                        + "전이표가 대신 막고 있다는 사실에 기대지 않는다 — S-1 의 선례")
                .isInstanceOf(CandidateTransitionException.class)
                .hasMessageContaining("selectedAt");

        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.ANALYZED);
        assertThat(candidate.getAttempt()).isZero();
    }

    @Test
    @DisplayName("시스템 판정으로 후보를 배제할 수 있다 — ANALYZED → REJECTED")
    void 구현_불가_판정이면_배제한다_S6() {
        ContributionCandidate candidate = analyzed();

        StatusTransition transition = candidate.rejectAsInfeasible(clock());

        assertThat(transition).isEqualTo(
                new StatusTransition(CandidateStatus.ANALYZED, CandidateStatus.REJECTED));
        assertThat(candidate.isTerminal()).isTrue();
        assertThat(candidate.isNotSelectedByHuman())
                .as("시스템 배제가 사람의 승인 흔적을 남겨서는 안 된다")
                .isTrue();
    }

    @Test
    @DisplayName("복구 불가 오류는 상한과 무관하게 FAILED 다 — IMPLEMENTING 출발")
    void 복구_불가_오류는_바로_FAILED_다_S6() {
        ContributionCandidate candidate = implementing();

        StatusTransition transition = candidate.fail(clock());

        assertThat(transition).isEqualTo(
                new StatusTransition(CandidateStatus.IMPLEMENTING, CandidateStatus.FAILED));
        assertThat(candidate.isTerminal()).isTrue();
        assertThat(candidate.getAttempt())
                .as("실패가 카운터를 건드리지 않는다 — 소진과 복구 불가는 다른 사유다")
                .isEqualTo(1);
    }

    // ─────────────────────── 불변식 ⑧ 재시도 상한 ───────────────────────

    @Test
    @DisplayName("한 바퀴에 attempt 가 1 오르고 TESTING·REVIEWING 은 올리지 않는다")
    void attempt_는_루프_한_바퀴를_센다_Q6() {
        ContributionCandidate candidate = implementing();
        assertThat(candidate.getAttempt()).isEqualTo(1);

        candidate.startTesting(clock());
        assertThat(candidate.getAttempt())
                .as("TESTING 은 같은 바퀴 안이다 — Q-6")
                .isEqualTo(1);

        candidate.startReview(clock());
        assertThat(candidate.getAttempt()).isEqualTo(1);

        candidate.retryImplementation(MAX_ATTEMPTS, clock());
        assertThat(candidate.getAttempt())
                .as("리뷰 실패로 되돌아가면 다음 바퀴다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("재시도 상한을 소진하면 FAILED 다")
    void 재시도_상한을_소진하면_FAILED_다_S6() {
        ContributionCandidate candidate = implementing();   // attempt = 1

        candidate.startTesting(clock());
        candidate.retryImplementation(MAX_ATTEMPTS, clock());   // attempt = 2
        candidate.startTesting(clock());
        candidate.retryImplementation(MAX_ATTEMPTS, clock());   // attempt = 3
        assertThat(candidate.getAttempt()).isEqualTo(MAX_ATTEMPTS);

        candidate.startTesting(clock());
        StatusTransition transition = candidate.retryImplementation(MAX_ATTEMPTS, clock());

        assertThat(transition.to()).isEqualTo(CandidateStatus.FAILED);
        assertThat(candidate.isTerminal())
                .as("상한 소진은 실패가 아니라 사람에게 넘기는 신호다 — S-6")
                .isTrue();
    }

    @Test
    @DisplayName("리뷰 실패로 소진해도 FAILED 다 — 테스트 실패와 같은 카운터")
    void 리뷰_실패도_같은_카운터를_소진한다_S6() {
        ContributionCandidate candidate = implementing();   // attempt = 1

        candidate.startTesting(clock());
        candidate.startReview(clock());
        candidate.retryImplementation(MAX_ATTEMPTS, clock());   // 리뷰 실패 → attempt = 2
        candidate.startTesting(clock());
        candidate.startReview(clock());
        candidate.retryImplementation(MAX_ATTEMPTS, clock());   // attempt = 3
        candidate.startTesting(clock());
        candidate.startReview(clock());

        StatusTransition transition = candidate.retryImplementation(MAX_ATTEMPTS, clock());

        assertThat(transition)
                .as("리뷰 실패가 테스트 실패와 같은 Error Analyzer 로 들어가고 게이트가 "
                        + "하나뿐이다 — Q-6 「합산」. REVIEWING 출발도 FAILED 로 가야 한다")
                .isEqualTo(new StatusTransition(CandidateStatus.REVIEWING, CandidateStatus.FAILED));
        assertThat(candidate.getAttempt()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("상한을 무한으로 만들 수 없다 — 큰 값이 본체다")
    void 상한을_무한으로_만들_수_없다_S6() {
        ContributionCandidate candidate = selected();

        assertThatThrownBy(() -> candidate.startImplementing(10_000, clock()))
                .as("설정 한 줄로 상한을 사실상 없애면 LLM 비용이 조용히 폭주하고 "
                        + "FAILED 신호가 사라진다 — 불변식 ⑧. 올리려면 도메인 상수를 고쳐야 한다")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> candidate.startImplementing(
                ContributionCandidate.MAX_ALLOWED_ATTEMPTS + 1, clock()))
                .as("절대 상한을 1 이라도 넘으면 거부한다")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> candidate.startImplementing(0, clock()))
                .as("0 은 무한이 아니라 최강 제약이지만, 의도된 값이 아니므로 함께 거부한다")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(candidate.getStatus())
                .as("거부된 호출이 상태를 움직여서는 안 된다")
                .isEqualTo(CandidateStatus.SELECTED);
    }

    // ─────────────────────── FR-9 분석 실패 ───────────────────────

    @Test
    @DisplayName("분석에 실패하면 FAILED 로 간다 — 후보가 박히지 않는다")
    void 분석에_실패하면_FAILED_로_간다_S6() {
        ContributionCandidate candidate = discovered();
        candidate.startAnalysis(clock());

        StatusTransition transition = candidate.failAnalysis(clock());

        assertThat(transition).isEqualTo(
                new StatusTransition(CandidateStatus.ANALYZING, CandidateStatus.FAILED));
        assertThat(candidate.isTerminal())
                .as("이 전이가 없으면 후보가 ANALYZING 에 영구히 박혀 사람이 볼 신호가 "
                        + "발생하지 않는다 — Q-6")
                .isTrue();
    }

    // ─────────────────────── 전이 계약 ───────────────────────

    @Test
    @DisplayName("전이는 이전 → 이후를 값으로 반환한다")
    void 전이는_이전과_이후를_반환한다() {
        ContributionCandidate candidate = discovered();

        StatusTransition transition = candidate.startAnalysis(clock());

        assertThat(transition.from()).isEqualTo(CandidateStatus.DISCOVERED);
        assertThat(transition.to()).isEqualTo(CandidateStatus.ANALYZING);
    }

    @Test
    @DisplayName("모든 전이가 updatedAt 을 갱신한다")
    void 전이는_updatedAt_을_갱신한다() {
        Instant later = T0.plus(Duration.ofMinutes(7));
        ContributionCandidate candidate = discovered();

        candidate.startAnalysis(at(later));

        assertThat(candidate.getUpdatedAt())
                .as("「마지막으로 상태가 움직인 시각」이 거짓이면 파이프라인 관측의 기본 축이 무너진다")
                .isEqualTo(later);
        assertThat(candidate.getCreatedAt())
                .as("생성 시각은 움직이지 않는다")
                .isEqualTo(T0);
    }

    @Test
    @DisplayName("같은 전이를 두 번 부르면 두 번째는 예외다")
    void 같은_전이를_두_번_부를_수_없다() {
        ContributionCandidate candidate = discovered();
        candidate.startAnalysis(clock());

        assertThatThrownBy(() -> candidate.startAnalysis(clock()))
                .as("조용히 통과시키면 중복 실행이 드러나지 않는다")
                .isInstanceOf(CandidateTransitionException.class);
    }

    @Test
    @DisplayName("불법 전이는 상태를 움직이지 않는다")
    void 불법_전이는_상태를_움직이지_않는다_S6() {
        ContributionCandidate candidate = discovered();

        assertThatThrownBy(() -> candidate.selectByHuman(clock()))
                .as("DISCOVERED 에서 바로 SELECTED 로 가면 분석 없이 사람이 고른 것이 된다")
                .isInstanceOf(CandidateTransitionException.class);

        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.DISCOVERED);
        assertThat(candidate.isNotSelectedByHuman())
                .as("실패한 selectByHuman 이 selectedAt 을 남기면 승인 증거가 거짓이 된다")
                .isTrue();
    }

    @Test
    @DisplayName("예외 메시지에 허용 목적지를 담아 원인을 말하게 한다")
    void 실패_메시지가_원인을_말한다() {
        ContributionCandidate candidate = discovered();

        assertThatThrownBy(() -> candidate.markReadyForPr(clock()))
                .hasMessageContaining("DISCOVERED")
                .hasMessageContaining("READY_FOR_PR")
                .hasMessageContaining("ANALYZING");
    }

    @Test
    @DisplayName("Clock 없이는 전이할 수 없다")
    void Clock_없이는_전이하지_않는다() {
        ContributionCandidate candidate = discovered();

        assertThatThrownBy(() -> candidate.startAnalysis(null))
                .as("updatedAt 을 채울 수 없는 전이를 허용하면 NOT NULL 컬럼이 깨진다")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
