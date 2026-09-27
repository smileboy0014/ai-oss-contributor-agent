package com.ossagent.candidate.domain;

/**
 * 한 단계의 판정 — #19.
 *
 * <h2>🔴 {@link #UNDETERMINED} 를 「실패」로 접지 않는다</h2>
 *
 * <p>계획서 rev.2 는 「판정 불가는 실패로 친다」였다. <b>그대로 짰으면 파이프라인이
 * 한 건도 통과하지 못한다.</b>
 *
 * <p>근거는 실측 두 개다.
 *
 * <table border="1">
 *   <caption>rev.2 가 틀린 이유</caption>
 *   <tr><th>걱정했던 것</th><th>실제</th></tr>
 *   <tr><td>「출력이 잘리면 종료코드를 믿을 수 없다」</td>
 *       <td>❌ <b>그 둘은 무관하다.</b> {@code DockerCodeSandbox} 는 파이프를 쓰지 않고
 *           종료코드를 데몬에서 직접 받는다. 절단은 <b>우리가 출력 버퍼를</b>
 *           {@code sandbox.max-output-chars}(기본 <b>200,000</b>)에서 자른 것일 뿐이다</td></tr>
 *   <tr><td>「절단은 드물다」</td>
 *       <td>❌ Gradle 빌드 로그는 <b>일상적으로</b> 200,000자를 넘는다.
 *           절단 = 판정 불가 = 실패로 접으면 <b>모든 후보가 코드 문제 없이
 *           {@code FAILED}</b> 로 떨어진다</td></tr>
 * </table>
 *
 * <p>그래서 <b>판정의 근거가 무엇이냐로 가른다.</b>
 *
 * <ul>
 *   <li><b>종료코드로 판정하는 단계</b>({@code COMPILE}·{@code TEST}) — 절단과 무관하다.
 *       {@link #PASSED} 아니면 {@link #FAILED} 다</li>
 *   <li><b>출력을 파싱해 판정하는 단계</b>({@code DIFF}) — 잘린 출력으로 「위반 없음」을
 *       말할 수 없다. 여기서만 {@link #UNDETERMINED} 가 나온다</li>
 * </ul>
 *
 * <p>⚠️ <b>이것은 S-5 의 「파싱 실패는 「허용」이 아니라 「보류」」와 같은 규칙이다.</b>
 * 다른 것은 <b>무엇을 파싱하느냐</b>뿐이다 — 거기서는 규약 문서, 여기서는 diff.
 * 종료코드는 애초에 파싱하는 것이 아니므로 그 규칙의 대상이 아니다.
 *
 * <p>🔴 <b>그리고 「보류」는 「통과」가 아니다.</b> {@link #UNDETERMINED} 는
 * {@link VerificationReport} 에서 <b>통과로 세지 않는다</b> — 사람이 본다.
 * 「모르면 되돌릴 수 없는 쪽을 피한다」({@code external-deps.md})가 여기 걸린다.
 */
public enum StageOutcome {

    /** 돌았고 통과했다. */
    PASSED,

    /** 돌았고 실패했다. 종료코드가 0 이 아니거나, 파싱이 위반을 찾았다. */
    FAILED,

    /**
     * 🔴 <b>판정할 근거가 없다.</b> 「통과」도 「실패」도 아니다.
     *
     * <table border="1">
     *   <caption>지금 이것이 나오는 자리 — 열거다</caption>
     *   <tr><th>단계</th><th>언제</th></tr>
     *   <tr><td>{@code DIFF}</td><td>출력이 {@code sandbox.max-output-chars} 에서 잘려
     *       「위반이 없다」를 말할 근거가 없을 때 · diff 명령 자체가 실패했을 때</td></tr>
     *   <tr><td>{@code TEST}</td><td>🔴 규약에서 <b>테스트 명령을 읽지 못했을 때</b>.
     *       돌리지 않은 테스트를 「통과」로 적지 않는다 (S-5)</td></tr>
     * </table>
     *
     * <p>⚠️ <b>{@link #SKIPPED} 와 헷갈리지 않는다.</b> 그쪽은 「앞 단계가 멈춰서 안 돌렸다」이고
     * 이것은 <b>「돌릴·판정할 근거가 없다」</b>다. 전자는 앞의 실패가 이미 설명하지만,
     * 후자는 <b>아무도 설명하지 않는 공백</b>이라 사람이 봐야 한다.
     *
     * <p>🔴 <b>이것이 나오면 재시도하지 않는다.</b> 같은 입력에 같은 결과이고
     * (테스트 명령은 재시도해도 생기지 않는다), 돌리면 Q-6 의 예산만 태우고 후보가
     * 코드 문제 없이 {@code FAILED} 로 떨어진다 —
     * {@link VerificationReport#hasUndetermined()} 가 그 분기점이다.
     *
     * <p>⚠️ 타임아웃은 여기가 아니라 {@link #FAILED} 다. 30분 안에 끝나지 않은 빌드는
     * <b>판정이 선 것</b>이고(「통과하지 못했다」), 보류로 두면 자동으로 풀릴 길이 없다.
     */
    UNDETERMINED,

    /**
     * 앞 단계가 멈춰서 <b>돌리지 않았다.</b>
     *
     * <p>기록을 아예 남기지 않는 것과 다르다 — 「돌렸는데 결과가 없다」와
     * 「돌리지 않기로 했다」를 구분하지 못하면, 나중에 로그를 보는 사람이
     * <b>단계가 조용히 빠진 것</b>을 알 수 없다.
     */
    SKIPPED;

    public boolean isPassed() {
        return this == PASSED;
    }

    /** 뒤 단계를 더 돌릴 이유가 있는가. 🔴 {@link #UNDETERMINED} 도 멈춘다. */
    public boolean allowsNextStage() {
        return this == PASSED;
    }
}
