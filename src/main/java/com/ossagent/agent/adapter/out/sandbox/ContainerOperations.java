package com.ossagent.agent.adapter.out.sandbox;

import com.github.dockerjava.api.model.HostConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Docker 제어 6개만 노출하는 <b>좁은 이음매</b> — #17.
 *
 * <h2>🔴 왜 층을 하나 더 두는가</h2>
 *
 * <p>{@code testing-philosophy.md} 가 못 박아 뒀다 —
 * 「<b>「타임아웃 시 컨테이너가 정리된다」는 능력 페이크로 증명되지 않는다</b> —
 * 페이크는 컨테이너를 만들지 않기 때문이다. 층을 하나 더 두는 이유가 이것이다」.
 *
 * <p>즉 {@code FakeCodeSandbox} 로는 S-3 의 「끝나면 지운다」를 검증할 수 없다.
 * 그것을 보려면 <b>docker 제어 호출을 기록하는 대역</b>이 필요하고, 이 인터페이스가
 * 그 대역이 구현할 표면이다.
 *
 * <p>⚠ {@code DockerClient} 를 직접 목으로 만들지 않는 이유 — fluent cmd 체인
 * ({@code client.createContainerCmd(…).withHostConfig(…).exec()})을 목으로 흉내 내면
 * 테스트가 <b>구현 세부</b>에 묶인다. 인자 하나를 옮기는 리팩토링에 테스트가 깨지고,
 * 정작 「제거가 불렸는가」는 보지 못한다.
 *
 * <p>⚠ 구현체({@code DockerContainerOperations})는 <b>로직을 갖지 않는다.</b>
 * docker-java 호출을 1:1 로 옮기고 예외를 번역할 뿐이다. 그래서 Docker 없이 검증할 수
 * 없고, <b>미검증으로 둔다</b> — 로직이 생기면 {@link DockerCodeSandbox} 로 올린다.
 * 「커버리지 100%」의 유일한 예외이고, 숨기지 않고 적는다.
 */
public interface ContainerOperations {

    /** 컨테이너를 만든다. 아직 돌지 않는다. */
    String create(ContainerCreation creation);

    void start(String containerId);

    /**
     * 종료를 기다린다.
     *
     * @return 상한을 넘겼으면 {@link WaitOutcome#timedOut()} 이 참. <b>예외가 아니다</b> —
     *         타임아웃은 이 제품에서 정상적으로 일어나는 일이고, 호출자가 kill 을 결정한다
     */
    WaitOutcome await(String containerId, Duration timeout);

    /**
     * 출력을 모은다.
     *
     * <p>🔴 {@code maxChars} 는 <b>메모리 상한</b>이다. 빌드 로그는 수백 MB 가 될 수 있어,
     * 다 받아 놓고 자르면 우리 프로세스가 죽는다. 상한에 닿으면 <b>더 쌓지 않는다</b> —
     * 스트림 자체는 끝나거나 {@code timeout} 에 걸릴 때까지 흐른다.
     *
     * <p>🔴 {@code timeout} 이 필요한 이유 — kill 직후 로그 조회가 매달릴 수 있다.
     * 여기서 멈추면 {@code finally} 의 {@link #remove} 에 도달하지 못해
     * <b>FR-5·FR-6 이 동시에 깨진다.</b>
     */
    ContainerLogs logs(String containerId, int maxChars, Duration timeout);

    void kill(String containerId);

    /**
     * 컨테이너를 지운다.
     *
     * @return 실제로 지워졌는가. 🔴 <b>실패를 예외로 던지지 않는다</b> — 정리 실패로
     *         원래 결과를 잃으면 안 되고, 대신 {@code cleanedUp=false} 로 보고한다
     */
    boolean remove(String containerId);

    /**
     * 워밍 전용 네트워크를 보장한다 — 없으면 만든다.
     *
     * <p>이름은 {@link SandboxProperties} 가 {@code host}·{@code container:} 를 거부한 뒤의
     * 값이다. 여기서 다시 검증하지 않는다 — 검증 자리가 둘이 되면 갈라진다.
     */
    void ensureNetwork(String name);

    /**
     * 컨테이너 생성 인자.
     *
     * @param image      {@code SandboxImages} 가 화이트리스트로 고른 값
     * @param argv       🔴 <b>쉘을 경유하지 않는다</b> — S-4
     * @param env        {@code KEY=VALUE}. 단계별 화이트리스트
     * @param workingDir 컨테이너 안 작업 디렉토리
     * @param hostConfig {@link SandboxContainerSpec} 이 만든 격리 설정
     * @param labels     누수 컨테이너 회수용 표시
     */
    record ContainerCreation(
            String image,
            List<String> argv,
            List<String> env,
            String workingDir,
            HostConfig hostConfig,
            Map<String, String> labels) {
    }

    /**
     * @param exitCode 종료코드. {@code timedOut} 이면 의미가 없다
     * @param timedOut 상한을 넘겨 강제 종료 대상이 됐는가
     */
    record WaitOutcome(int exitCode, boolean timedOut) {

        static WaitOutcome exited(int exitCode) {
            return new WaitOutcome(exitCode, false);
        }

        static WaitOutcome timeout() {
            return new WaitOutcome(-1, true);
        }
    }

    /**
     * @param output    stdout+stderr 합본
     * @param truncated 🔴 상한에 닿아 끊겼는가. <b>숨기지 않는다</b> — 잘린 로그를 LLM 이
     *                  「전부」로 읽고 「테스트가 통과했다」로 판단하면 게이트가 무력해진다
     */
    record ContainerLogs(String output, boolean truncated) {

        static ContainerLogs empty() {
            return new ContainerLogs("", false);
        }
    }
}
