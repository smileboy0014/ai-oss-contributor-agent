package com.ossagent.candidate.domain;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 검증 한 바퀴의 결과 전체 — #19.
 *
 * <h2>🔴 「통과」의 정의를 한 곳에만 둔다</h2>
 *
 * <p>{@link #passed()} 는 <b>모든 단계가 {@link StageOutcome#PASSED}</b> 일 때만 참이다.
 * 「실패가 없으면 통과」로 적지 않는다 — 그러면 {@link StageOutcome#UNDETERMINED} 와
 * {@link StageOutcome#SKIPPED} 가 조용히 통과로 접힌다.
 *
 * <table border="1">
 *   <caption>같아 보이지만 전혀 다른 두 술어</caption>
 *   <tr><th>표현</th><th>UNDETERMINED 3건일 때</th></tr>
 *   <tr><td>{@code stages.noneMatch(FAILED)}</td><td>🔴 <b>통과</b> — S-5 가 막는 바로 그 접기</td></tr>
 *   <tr><td>{@code stages.allMatch(PASSED)}</td><td>✅ <b>통과 아님</b></td></tr>
 * </table>
 *
 * <p>⚠️ 이 구분이 {@code StageOutcome} 에 네 값을 둔 이유 전부다. 값을 넷으로 갈라 놓고
 * 판정을 「실패가 아니면 통과」로 적으면, 갈라 둔 의미가 그 한 줄에서 사라진다.
 *
 * <h2>비었으면 통과가 아니다</h2>
 *
 * <p>{@code allMatch} 는 빈 목록에 <b>참</b>이다. 단계를 하나도 돌리지 않은 보고서가
 * 「통과」가 되면 <b>검증을 건너뛴 것이 통과로 보고</b>된다 — 「0건을 검사하고 초록」
 * ({@code testing-philosophy.md} 요구 1)의 런타임판이다. 생성자가 빈 목록을 거부한다.
 *
 * @param stages 단계별 결과. {@link VerificationStage} 선언 순서대로이고,
 *               멈춘 뒤의 단계도 {@link StageOutcome#SKIPPED} 로 <b>들어 있다</b>
 */
public record VerificationReport(List<StageResult> stages) {

    public VerificationReport {
        if (stages == null || stages.isEmpty()) {
            // 🔴 빈 보고서를 「통과」로 만들지 않는다 — 위 javadoc
            throw new IllegalArgumentException("검증 보고서에 단계가 하나도 없다");
        }
        stages = List.copyOf(stages);
    }

    /** 🔴 <b>모두</b> {@link StageOutcome#PASSED} 여야 한다. 위 javadoc 의 표를 먼저 읽는다. */
    public boolean passed() {
        return stages.stream().allMatch(StageResult::isPassed);
    }

    /**
     * 사람이 봐야 하는가 — 판정이 서지 않은 단계가 있는가.
     *
     * <p>{@link #passed()} 가 거짓인 이유가 <b>{@code FAILED}(코드 문제)인지
     * {@code UNDETERMINED}(우리가 판정 못 함)인지</b>를 가른다. 둘을 뭉뚱그리면
     * 재시도 루프가 <b>고칠 것이 없는 코드를 세 번 고치려 든다</b>(Q-6 의 예산을 태운다).
     */
    public boolean hasUndetermined() {
        return stages.stream().anyMatch(it -> it.outcome() == StageOutcome.UNDETERMINED);
    }

    /** 멈춘 자리 — 처음으로 통과하지 못한 단계. 전부 통과했으면 비어 있다. */
    public Optional<StageResult> firstNotPassed() {
        return stages.stream().filter(it -> !it.isPassed()).findFirst();
    }

    public Optional<StageResult> stage(VerificationStage stage) {
        return stages.stream().filter(it -> it.stage() == stage).findFirst();
    }

    public Duration totalDuration() {
        return stages.stream().map(StageResult::duration).reduce(Duration.ZERO, Duration::plus);
    }

    /**
     * 실패 사유로 쓸 요약 — <b>통과하지 못한 첫 단계의 것</b>.
     *
     * <p>이미 {@link StageResult} 생성자에서 스크럽·절단됐다. 여기서 다시 조립하지 않는
     * 이유는 여러 단계의 출력을 이으면 <b>재시도 프롬프트가 상한 없이 자라기</b> 때문이다 —
     * 첫 실패에서 멈추므로 실제로 내용이 있는 단계는 하나뿐이기도 하다.
     */
    public String failureSummary() {
        return firstNotPassed().map(StageResult::summary).orElse("");
    }

    /** 로그·보고용 집계. 🔴 요약 본문은 담지 않는다. */
    public Map<VerificationStage, StageOutcome> outcomes() {
        Map<VerificationStage, StageOutcome> map = new EnumMap<>(VerificationStage.class);
        stages.forEach(it -> map.put(it.stage(), it.outcome()));
        return map;
    }

    @Override
    public String toString() {
        return "VerificationReport[passed=%s, undetermined=%s, outcomes=%s, totalMs=%d]"
                .formatted(passed(), hasUndetermined(), outcomes(), totalDuration().toMillis());
    }
}
