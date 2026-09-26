package com.ossagent.agent.adapter.out.sandbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * docker 제어 호출을 <b>순서대로 기록</b>하는 대역 — #17 · S-3.
 *
 * <p>{@code testing-philosophy.md} 가 요구한 층이다 — 「「타임아웃 시 컨테이너가 정리된다」는
 * 능력 페이크로 증명되지 않는다. 페이크는 컨테이너를 만들지 않기 때문이다」.
 *
 * <p>🔴 <b>순서가 검증 대상이다.</b> 「kill 과 remove 가 둘 다 불렸다」로는 부족하다 —
 * 살아 있는 컨테이너를 제거하려 들면 데몬이 거부하거나 강제 종료가 되는데, 그러면
 * 「정리했다」는 보고가 사실과 달라진다.
 *
 * <p>실패 모드를 재현할 수 있어야 한다 — 「항상 성공하는 대역」은 게이트를 검증하지 못한다.
 */
class RecordingContainerOperations implements ContainerOperations {

    static final String CONTAINER_ID = "container-1";

    private final List<String> calls = new ArrayList<>();
    private final List<String> ensuredNetworks = new ArrayList<>();
    private ContainerCreation creation;

    private WaitOutcome waitOutcome = WaitOutcome.exited(0);
    private ContainerLogs logs = new ContainerLogs("ok", false);
    private boolean removeSucceeds = true;
    private RuntimeException failCreate;
    private RuntimeException failStart;
    private RuntimeException failLogs;
    private RuntimeException failRemove;

    // ── 기록 ────────────────────────────────────────────────

    List<String> calls() {
        return List.copyOf(calls);
    }

    ContainerCreation creation() {
        return creation;
    }

    List<String> ensuredNetworks() {
        return List.copyOf(ensuredNetworks);
    }

    // ── 실패 모드 ────────────────────────────────────────────

    RecordingContainerOperations thenTimeout() {
        this.waitOutcome = WaitOutcome.timeout();
        return this;
    }

    RecordingContainerOperations thenExitWith(int exitCode) {
        this.waitOutcome = WaitOutcome.exited(exitCode);
        return this;
    }

    RecordingContainerOperations thenLogs(String output, boolean truncated) {
        this.logs = new ContainerLogs(output, truncated);
        return this;
    }

    RecordingContainerOperations thenRemoveFails() {
        this.removeSucceeds = false;
        return this;
    }

    RecordingContainerOperations thenRemoveThrows(RuntimeException e) {
        this.failRemove = e;
        return this;
    }

    RecordingContainerOperations thenCreateThrows(RuntimeException e) {
        this.failCreate = e;
        return this;
    }

    RecordingContainerOperations thenStartThrows(RuntimeException e) {
        this.failStart = e;
        return this;
    }

    RecordingContainerOperations thenLogsThrows(RuntimeException e) {
        this.failLogs = e;
        return this;
    }

    // ── 구현 ────────────────────────────────────────────────

    @Override
    public String create(ContainerCreation creation) {
        calls.add("create");
        this.creation = creation;
        if (failCreate != null) {
            throw failCreate;
        }
        return CONTAINER_ID;
    }

    @Override
    public void start(String containerId) {
        calls.add("start");
        if (failStart != null) {
            throw failStart;
        }
    }

    @Override
    public WaitOutcome await(String containerId, Duration timeout) {
        calls.add("await");
        return waitOutcome;
    }

    @Override
    public ContainerLogs logs(String containerId, int maxChars, Duration timeout) {
        calls.add("logs");
        if (failLogs != null) {
            throw failLogs;
        }
        return logs;
    }

    @Override
    public void kill(String containerId) {
        calls.add("kill");
    }

    @Override
    public boolean remove(String containerId) {
        calls.add("remove");
        if (failRemove != null) {
            throw failRemove;
        }
        return removeSucceeds;
    }

    @Override
    public void ensureNetwork(String name) {
        calls.add("ensureNetwork");
        ensuredNetworks.add(name);
    }
}
