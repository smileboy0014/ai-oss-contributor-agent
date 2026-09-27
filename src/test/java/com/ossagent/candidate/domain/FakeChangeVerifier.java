package com.ossagent.candidate.domain;

import com.ossagent.support.testing.FakeAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChangeVerifier} 대역 — Q-9 의 「능력 대역」 층.
 *
 * <p>업무 흐름 테스트(#18 의 착수 → 검증 → 리뷰)는 <b>보고서를 직접 주는 편</b>이 맞다.
 * 단계 순서·판정·스크럽은 페이크 {@code CodeSandbox} 를 끼운
 * {@code SandboxChangeVerifierTest} 가 본다.
 *
 * <p>🔴 <b>실패 모드를 재현할 수 있어야 한다.</b> 「항상 통과하는 페이크」는
 * 게이트를 검증하지 못한다 — 이 제품의 품질 축은 「나쁜 결과를 걸러내는가」다.
 * 특히 {@link #givenUndetermined()} 가 중요하다: 그 상태에서 <b>재시도하지 않는 것</b>이
 * 호출자의 계약이고({@link VerificationReport#hasUndetermined()}), 대역이 그것을 만들지
 * 못하면 아무도 검증할 수 없다.
 *
 * <p>⚠ 싱글턴이고 컨텍스트가 테스트 클래스 사이에 캐시된다. 호출 기록을 단언하는
 * 테스트는 {@link #reset()} 을 {@code @BeforeEach} 에서 부른다.
 */
@FakeAdapter
public class FakeChangeVerifier implements ChangeVerifier {

    private final List<VerificationRequest> requests = new ArrayList<>();

    private VerificationReport nextReport = passingReport();
    private RuntimeException nextFailure;

    /**
     * 🔴 <b>바퀴별 결과</b> — #21 의 루프를 검증하려면 이것이 있어야 한다.
     *
     * <p>고정 결과 하나만 돌려주면 「1바퀴 돌고 실패」와 「3바퀴 돌고 실패」가
     * <b>상태로 구분되지 않는다</b>(둘 다 {@code FAILED}). 바퀴마다 다른 것을 주고
     * {@link #requests()} 로 <b>몇 번 불렸나</b>를 세는 것이 유일한 증거다.
     *
     * <p>비어 있으면 {@link #nextReport} 로 떨어진다 — 기존 테스트가 그대로 돈다.
     */
    private final java.util.Deque<VerificationReport> scripted = new java.util.ArrayDeque<>();

    public void reset() {
        requests.clear();
        scripted.clear();
        nextReport = passingReport();
        nextFailure = null;
    }

    /** 바퀴 순서대로 돌려준다. 다 쓰면 마지막 것이 반복된다. */
    public FakeChangeVerifier givenInOrder(VerificationReport... reports) {
        scripted.clear();
        scripted.addAll(java.util.List.of(reports));
        return this;
    }

    /** 이 대역이 받은 요청들. 「몇 번째 attempt 로 불렸나」를 단언할 때 쓴다. */
    public List<VerificationRequest> requests() {
        return List.copyOf(requests);
    }

    // ── 결과 모드 ────────────────────────────────────────────

    public FakeChangeVerifier given(VerificationReport report) {
        this.nextReport = report;
        return this;
    }

    /** 전부 통과. */
    public FakeChangeVerifier givenPassing() {
        return given(passingReport());
    }

    /** 🔴 테스트가 깨졌다 — <b>재시도가 의미 있는</b> 실패다. */
    public FakeChangeVerifier givenTestFailure() {
        return given(new VerificationReport(List.of(
                passed(VerificationStage.COMPILE),
                new StageResult(VerificationStage.TEST, StageOutcome.FAILED, 1,
                        Duration.ofSeconds(1), false, "2 tests failed"),
                StageResult.skipped(VerificationStage.DIFF))));
    }

    /** 🔴 계획 범위 밖 파일을 고쳤다. */
    public FakeChangeVerifier givenDiffViolation() {
        return given(new VerificationReport(List.of(
                passed(VerificationStage.COMPILE),
                passed(VerificationStage.TEST),
                new StageResult(VerificationStage.DIFF, StageOutcome.FAILED, 0,
                        Duration.ofSeconds(1), false, "계획에 없는 파일이 바뀌었다"))));
    }

    /**
     * 🔴 <b>판정할 근거가 없다</b> — 재시도가 <b>의미 없는</b> 실패다.
     *
     * <p>{@link #givenTestFailure()} 와 이것을 같은 것으로 다루면 Q-6 의 예산이
     * 고칠 수 없는 것에 통째로 소진된다.
     */
    public FakeChangeVerifier givenUndetermined() {
        return given(new VerificationReport(List.of(
                passed(VerificationStage.COMPILE),
                new StageResult(VerificationStage.TEST, StageOutcome.UNDETERMINED, null,
                        Duration.ZERO, false, "규약에서 테스트 명령을 읽지 못했다"),
                StageResult.skipped(VerificationStage.DIFF))));
    }

    /** 검증을 <b>시작조차 못 했다.</b> 이때만 예외다. */
    public FakeChangeVerifier thenFailWith(RuntimeException failure) {
        this.nextFailure = failure;
        return this;
    }

    // ── 구현 ────────────────────────────────────────────────

    @Override
    public VerificationReport verify(VerificationRequest request) {
        requests.add(request);
        if (nextFailure != null) {
            throw nextFailure;
        }
        // ⚠ 마지막 하나는 남겨 둔다 — 상한을 넘겨 불리면 「대역이 바닥났다」가 아니라
        //   「같은 실패가 계속된다」가 되어야 루프를 그대로 재현한다
        if (scripted.size() > 1) {
            return scripted.poll();
        }
        return scripted.isEmpty() ? nextReport : scripted.peek();
    }

    /** 전부 통과한 보고서 — 테스트가 스크립트에 쓴다. */
    public static VerificationReport passing() {
        return passingReport();
    }

    /** 테스트가 깨진 보고서 — <b>재시도가 의미 있는</b> 실패다. */
    public static VerificationReport testFailure(String summary) {
        return new VerificationReport(List.of(
                passed(VerificationStage.COMPILE),
                new StageResult(VerificationStage.TEST, StageOutcome.FAILED, 1,
                        Duration.ofSeconds(1), false, summary),
                StageResult.skipped(VerificationStage.DIFF)));
    }

    private static VerificationReport passingReport() {
        return new VerificationReport(List.of(
                passed(VerificationStage.COMPILE),
                passed(VerificationStage.TEST),
                passed(VerificationStage.DIFF)));
    }

    private static StageResult passed(VerificationStage stage) {
        return new StageResult(stage, StageOutcome.PASSED, 0, Duration.ofSeconds(1), false, "");
    }
}
