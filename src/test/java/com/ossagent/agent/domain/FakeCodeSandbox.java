package com.ossagent.agent.domain;

import com.ossagent.support.testing.FakeAdapter;
import java.time.Duration;
import java.util.ArrayList;
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

    private SandboxResult nextResult = success();
    private RuntimeException nextFailure;

    public void reset() {
        commands.clear();
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
        return nextResult;
    }

    private static SandboxResult success() {
        return new SandboxResult(0, "ok", false, false, Duration.ofSeconds(1), true);
    }
}
