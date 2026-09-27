package com.ossagent.agent.domain;

import com.ossagent.support.testing.FakeAdapter;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@link CodeSandbox} 대역 — Q-9.
 *
 * <p>자동 스위트는 <b>샌드박스 컨테이너를 띄우지 않는다.</b> 우리가 돌리는 것은 신뢰할 수
 * 없는 코드이고(S-3), 한 번에 수 분이며, CI 에서 Docker 를 가정할 수 없다.
 *
 * <p>🔴 <b>실패 모드를 재현할 수 있어야 한다</b> — 타임아웃 · 0 아닌 종료코드 · 출력 절단 ·
 * 정리 실패 · 예외. 「항상 성공하는 페이크」는 게이트를 검증하지 못한다. 이 제품의 품질 축은
 * <b>「나쁜 결과를 걸러내는가」</b>이고, 그것을 검증하려면 나쁜 결과를 만들 수 있어야 한다.
 *
 * <p>⚠ <b>이 대역으로 증명되지 않는 것이 있다</b> — 「타임아웃 시 컨테이너가 정리된다」.
 * 페이크는 컨테이너를 만들지 않기 때문이다. 그것은 {@code RecordingContainerOperations} 가
 * 한 층 아래에서 본다.
 *
 * <p>⚠ 싱글턴이고 컨텍스트가 테스트 클래스 사이에 캐시된다. 호출 기록을 단언하는 테스트는
 * {@link #reset()} 을 {@code @BeforeEach} 에서 부른다.
 */
@FakeAdapter
public class FakeCodeSandbox implements CodeSandbox {

    private final List<SandboxCommand> commands = new ArrayList<>();

    private final Deque<SandboxResult> queued = new ArrayDeque<>();

    private SandboxResult nextResult = success();
    private RuntimeException nextFailure;

    public void reset() {
        commands.clear();
        queued.clear();
        nextResult = success();
        nextFailure = null;
    }

    /** 이 대역이 받은 명령들. 단계 순서(워밍 → 씨딩 → 실행)를 단언할 때 쓴다. */
    public List<SandboxCommand> commands() {
        return List.copyOf(commands);
    }

    // ── 실패 모드 ────────────────────────────────────────────

    public FakeCodeSandbox given(SandboxResult result) {
        this.nextResult = result;
        return this;
    }

    /** 0 이 아닌 종료코드 — 빌드가 실패했다. <b>예외가 아니다</b>. */
    public FakeCodeSandbox givenBuildFailure(int exitCode) {
        return given(new SandboxResult(exitCode, "build failed", false, false,
                Duration.ofSeconds(1), true));
    }

    /** 상한을 넘겨 강제 종료됐다. 종료코드를 믿으면 안 된다. */
    public FakeCodeSandbox givenTimeout() {
        return given(new SandboxResult(-1, "", false, true, Duration.ofMinutes(30), true));
    }

    /** 출력이 잘렸다 — 하류가 「전부」로 읽으면 게이트가 무력해진다. */
    public FakeCodeSandbox givenTruncatedOutput(String partial) {
        return given(new SandboxResult(0, partial, true, false, Duration.ofSeconds(1), true));
    }

    /** 컨테이너가 남았다 — 누수. 실행 결과와는 별개의 사실이다. */
    public FakeCodeSandbox givenCleanupFailure() {
        return given(new SandboxResult(0, "ok", false, false, Duration.ofSeconds(1), false));
    }

    // ── 호출별 결과 (#19 이 더했다) ─────────────────────────────

    /**
     * 🔴 <b>호출 순서대로</b> 결과를 돌려준다 — {@link #given(SandboxResult)} 와 다르다.
     *
     * <p>검증 파이프라인(#19)은 한 바퀴에 샌드박스를 <b>여러 번</b> 부른다
     * (컴파일 · 테스트 · diff 목록 · diff 본문). 결과가 하나뿐이면 「컴파일은 통과하고
     * 테스트는 실패한다」 같은 <b>단계별 분기를 표현할 수 없고</b>, 그러면
     * 「첫 실패에서 멈춘다」·「뒤 단계는 SKIPPED」를 검증할 방법이 없다.
     *
     * <p>⚠️ 큐가 <b>비면</b> {@link #given(SandboxResult)} 의 값으로 되돌아간다 —
     * 큐를 소진한 뒤의 호출이 조용히 예외가 되면 「몇 번 불렸나」를 테스트가
     * 단언하기 전에 죽는다.
     */
    public FakeCodeSandbox givenSequence(SandboxResult... results) {
        queued.clear();
        for (SandboxResult result : results) {
            queued.add(result);
        }
        return this;
    }

    /** 종료코드 0 · 주어진 출력. {@link #givenSequence} 의 재료다. */
    public static SandboxResult ok(String output) {
        return new SandboxResult(0, output, false, false, Duration.ofSeconds(1), true);
    }

    /** 0 이 아닌 종료코드. {@link #givenSequence} 의 재료다. */
    public static SandboxResult exitedWith(int exitCode, String output) {
        return new SandboxResult(exitCode, output, false, false, Duration.ofSeconds(1), true);
    }

    /** 출력이 잘렸다. {@link #givenSequence} 의 재료다. */
    public static SandboxResult truncated(String partial) {
        return new SandboxResult(0, partial, true, false, Duration.ofSeconds(1), true);
    }

    /** 실행 자체를 못 했다. <b>이때만</b> 예외다. */
    public FakeCodeSandbox thenFailWith(RuntimeException failure) {
        this.nextFailure = failure;
        return this;
    }

    // ── 구현 ────────────────────────────────────────────────

    @Override
    public SandboxResult run(SandboxCommand command) {
        commands.add(command);
        if (nextFailure != null) {
            throw nextFailure;
        }
        SandboxResult queuedResult = queued.poll();
        return queuedResult != null ? queuedResult : nextResult;
    }

    private static SandboxResult success() {
        return new SandboxResult(0, "ok", false, false, Duration.ofSeconds(1), true);
    }
}
