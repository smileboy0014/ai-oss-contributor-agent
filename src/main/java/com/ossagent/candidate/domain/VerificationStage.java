package com.ossagent.candidate.domain;

/**
 * 검증 파이프라인의 단계 — PRD §15 · #19.
 *
 * <p>순서대로 돌고 <b>첫 실패에서 멈춘다.</b> 컴파일이 깨졌는데 테스트를 30분 돌릴 이유가
 * 없다. 멈춘 뒤의 단계는 {@link StageOutcome#SKIPPED} 로 <b>명시</b>한다 —
 * 기록이 없는 것과 건너뛴 것은 다르다.
 *
 * <h2>🔴 다섯이 아니라 셋인 이유 — 둘은 입력이 없다</h2>
 *
 * <p>이슈 #19 는 「컴파일 → 유닛 → 통합 → 포맷/린트 → diff」 다섯을 그렸다.
 * 계획서 rev.3 이 둘을 뺐다. <b>이름만 있는 칸을 두면 다음 사람이 채우려다 더 나쁜 것을
 * 만든다.</b>
 *
 * <table border="1">
 *   <caption>뺀 둘</caption>
 *   <tr><th>단계</th><th>왜 없나</th></tr>
 *   <tr><td><b>포맷/린트</b></td><td>🔴 <b>명령의 출처가 없다.</b>
 *       {@code RepositoryPolicy} 에 {@code javaVersion}·{@code buildCommand}·
 *       {@code testCommand} 뿐이고 포맷 명령 필드가 없다. 우리가 {@code spotlessCheck} 류를
 *       하드코딩하면 <b>대상 저장소의 규약을 우리 어휘로 대체</b>하는 것이고(S-5),
 *       그 태스크가 없는 저장소에서는 <b>코드 문제가 없는데 실패</b>한다.
 *       ⚠️ 게다가 {@code spotlessApply} 를 고르면 <b>검증 단계가 스스로 「계획 범위 밖
 *       변경」을 만들고</b> 바로 다음 {@link #DIFF} 가 그것을 위반으로 잡는다 —
 *       검증이 검증 대상을 오염시킨다</td></tr>
 *   <tr><td><b>통합 테스트 분리</b></td><td>{@code testCommand} 는 <b>하나</b>다.
 *       그리고 실행 단계는 <b>{@code network=none}</b> 이다(S-3 · Q-4) — 대상 저장소의
 *       통합 테스트는 Testcontainers·브로커를 요구하는 것이 보통이라
 *       <b>정상 코드가 실패</b>한다. Docker 소켓 마운트는 S-3 금지라 우회로도 없다</td></tr>
 * </table>
 *
 * <p>둘 다 <b>데이터가 생기면</b> 단계를 만든다 — #7 이 포맷 명령을 뽑거나,
 * Q-4 가 네트워크를 요구하는 테스트를 어떻게 다룰지 정하면.
 */
public enum VerificationStage {

    /** 컴파일. {@code buildCommand} 로 돈다. 판정은 <b>종료코드만</b> 본다. */
    COMPILE,

    /**
     * 테스트. {@code testCommand} 로 돈다. 판정은 <b>종료코드만</b> 본다.
     *
     * <p>⚠️ 유닛과 통합을 <b>가르지 않는다</b> — 위 표 참조.
     */
    TEST,

    /**
     * 생성된 diff 검사 — 계획 범위 밖 파일 · 디버그 잔재 · 대용량 바이너리.
     *
     * <p>🔴 <b>이 단계만 출력을 파싱한다.</b> 그래서 출력이 잘리면
     * {@link StageOutcome#UNDETERMINED} 가 나올 수 있는 <b>유일한 단계</b>다 —
     * {@code StageOutcome} javadoc 이 그 이유를 적어 뒀다.
     */
    DIFF
}
