package com.ossagent.agent.domain;

import com.ossagent.support.ExternalText;
import java.time.Duration;

/**
 * 샌드박스 실행 1회의 결과 — #17.
 *
 * <h2>🔴 빌드 실패는 예외가 아니라 결과다</h2>
 *
 * <p>0 이 아닌 종료코드는 오류가 아니라 <b>게이트가 작동한 것</b>이다. 이 제품의 품질 축은
 * 「나쁜 결과를 걸러내는가」이고, 그 판정이 예외로 나가면 호출자가 재시도 루프에서 삼킨다.
 * 예외({@link SandboxException})는 <b>실행 자체를 못 한 경우</b>에만 쓴다.
 *
 * @param exitCode  컨테이너 종료코드. 타임아웃이면 강제 종료된 값이라 의미가 없다
 * @param output    stdout+stderr 합본. <b>잘렸을 수 있다</b> — {@link #truncated}
 * @param truncated 출력이 상한에 닿아 끊겼는가. 🔴 <b>숨기지 않는다</b> — 아래
 * @param timedOut  실행 시간 상한을 넘겨 강제 종료됐는가
 * @param duration  실제 소요 시간
 * @param cleanedUp 컨테이너가 실제로 제거됐는가. 🔴 <b>false 면 누수다</b> — 아래
 */
public record SandboxResult(
        int exitCode,
        @ExternalText(ExternalText.Source.BUILD_OUTPUT) String output,
        boolean truncated,
        boolean timedOut,
        Duration duration,
        boolean cleanedUp) {

    public SandboxResult {
        output = output == null ? "" : output;
        duration = duration == null ? Duration.ZERO : duration;
    }

    /**
     * 실행이 성공했는가.
     *
     * <p>⚠ 타임아웃은 종료코드와 무관하게 실패다. 강제 종료된 컨테이너의 종료코드를
     * 믿으면 안 된다.
     */
    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }

    /**
     * 🔴 출력이 잘렸다는 사실을 <b>반드시 하류에 전달한다.</b>
     *
     * <p>잘린 로그를 LLM 이 「전부」로 읽고 「테스트가 통과했다」로 판단하면 게이트가
     * 무력해진다. 이 제품에서 그것은 기능 저하가 아니라 <b>제품 훼손</b>이다.
     */
    public boolean outputIsComplete() {
        return !truncated;
    }

    /**
     * 🔴 대상 저장소 출력을 <b>담지 않는다</b> — 로그 인젝션 · 시크릿 유출 (S-4).
     *
     * <p>빌드 출력에는 대상 저장소가 커밋해 둔 시크릿이 섞여 있을 수 있다.
     * 저장은 {@code GeneratedChange} 의 몫이고(#18·#19), 로그는 「무슨 일이 일어났나」만 남긴다.
     */
    @Override
    public String toString() {
        return "SandboxResult[exitCode=%d, timedOut=%s, truncated=%s, outputLength=%d, duration=%s, cleanedUp=%s]"
                .formatted(exitCode, timedOut, truncated, output.length(), duration, cleanedUp);
    }
}
