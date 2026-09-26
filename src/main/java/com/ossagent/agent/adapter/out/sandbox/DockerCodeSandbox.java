package com.ossagent.agent.adapter.out.sandbox;

import com.ossagent.agent.domain.CodeSandbox;
import com.ossagent.agent.domain.SandboxCommand;
import com.ossagent.agent.domain.SandboxResult;
import com.ossagent.agent.domain.WarmCommand;
import com.ossagent.support.ExternalAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 컨테이너 수명주기를 조율한다 — #17 · S-3.
 *
 * <pre>
 * create → start → await(timeout) ─┬─ 정상 → 로그 수집(상한·타임아웃)
 *                                  └─ 초과 → kill
 *                                            ↓
 *                       finally: remove · 실패하면 결과와 로그에 남긴다
 * </pre>
 *
 * <h2>🔴 {@code finally} 로도 부족하다</h2>
 *
 * <p>제거가 실패할 수 있다(데몬 장애 · 이미 사라짐). 조용히 넘기면 컨테이너가 쌓이고,
 * 그것은 시간이 지나야 드러나는 종류의 고장이다. {@link SandboxResult#cleanedUp()} 로
 * 호출자에게 알리고 {@code WARN} 을 남긴다 — 이슈가 말한 「정리 결과 로그」가 이것이다.
 *
 * <h2>🔴 빌드 실패는 예외가 아니다</h2>
 *
 * <p>0 이 아닌 종료코드는 <b>게이트가 작동한 것</b>이지 오류가 아니다. 예외로 내보내면
 * 호출자가 재시도 루프에서 삼킨다. 예외는 <b>실행 자체를 못 한 경우</b>에만 난다.
 *
 * <p>⚠ 격리 설정은 여기서 만들지 않는다 — {@link SandboxContainerSpec} 한 곳이다.
 * 여기서 {@code HostConfig} 를 손대기 시작하면 「어느 경로로는 네트워크가 열린다」가 생긴다.
 */
@Component
@ExternalAdapter
public class DockerCodeSandbox implements CodeSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerCodeSandbox.class);

    private final ContainerOperations operations;
    private final SandboxProperties properties;
    private final SandboxInstanceId instanceId;
    private final Clock clock;

    public DockerCodeSandbox(ContainerOperations operations, SandboxProperties properties,
            SandboxInstanceId instanceId, Clock clock) {
        this.operations = operations;
        this.properties = properties;
        this.instanceId = instanceId;
        this.clock = clock;
    }

    @Override
    public SandboxResult run(SandboxCommand command) {
        if (command instanceof WarmCommand) {
            // 네트워크가 열리는 유일한 단계다. 이름은 이미 host·container 가 거부된 값이고,
            // 여기서 다시 검증하지 않는다 — 검증 자리가 둘이 되면 갈라진다
            operations.ensureNetwork(properties.warmNetwork());
        }

        Instant startedAt = clock.instant();
        String containerId = null;
        int exitCode = -1;
        boolean timedOut = false;
        ContainerOperations.ContainerLogs logs = ContainerOperations.ContainerLogs.empty();
        boolean cleanedUp;

        try {
            containerId = operations.create(creationOf(command));
            operations.start(containerId);

            ContainerOperations.WaitOutcome outcome =
                    operations.await(containerId, command.limits().timeout());
            timedOut = outcome.timedOut();
            exitCode = outcome.exitCode();

            if (timedOut) {
                // 🔴 상한을 넘긴 컨테이너를 그대로 두면 상한이 상한이 아니다
                operations.kill(containerId);
            }

            // kill 뒤에도 로그를 모은다 — 무엇을 하다 멈췄는지가 진단의 전부다.
            // ⚠ 여기서 매달리면 아래 제거에 도달하지 못하므로 자체 타임아웃이 있다
            logs = collectLogsQuietly(containerId);
        } finally {
            cleanedUp = removeQuietly(containerId);
        }

        SandboxResult result = new SandboxResult(exitCode, logs.output(), logs.truncated(),
                timedOut, Duration.between(startedAt, clock.instant()), cleanedUp);

        // 🔴 대상 저장소 출력을 로그에 싣지 않는다 — 로그 인젝션 · 시크릿 유출 (S-4).
        //    SandboxResult.toString 이 길이만 남기도록 만들어져 있다
        log.info("샌드박스 실행 완료 stage={} {}", command.getClass().getSimpleName(), result);
        return result;
    }

    /**
     * 🔴 로그를 못 받았다고 <b>실행 결과를 버리지 않는다.</b>
     *
     * <p>여기서 예외를 전파하면 {@link SandboxResult} 자체가 만들어지지 않아
     * {@code exitCode}·{@code timedOut}·{@code cleanedUp} 이 통째로 사라진다.
     * <b>실행은 이미 끝났는데</b> 그 사실을 호출자가 알 수 없게 되는 것이다 —
     * 「빌드 실패는 예외가 아니라 결과다」라는 이 클래스의 전제와 어긋난다.
     *
     * <p>로그가 없는 것은 {@code truncated} 로 표시해 하류가 알게 한다.
     */
    private ContainerOperations.ContainerLogs collectLogsQuietly(String containerId) {
        try {
            return operations.logs(containerId, properties.maxOutputChars(), properties.logTimeout());
        } catch (RuntimeException e) {
            log.warn("로그를 수집하지 못했다 containerId={} — 실행 결과는 그대로 돌려준다",
                    containerId, e);
            return new ContainerOperations.ContainerLogs("", true);
        }
    }

    /**
     * 🔴 정리 실패로 원래 결과를 잃지 않는다.
     *
     * <p>여기서 예외를 던지면 {@code finally} 안에서 나가는 예외가 되어 <b>본래 실패를
     * 덮어쓴다.</b> 진짜 원인이 「제거 실패」로 바뀌어 진단이 엉뚱해진다.
     *
     * @return 컨테이너가 남지 않았는가. 만들지 못했으면 남은 것도 없다
     */
    private boolean removeQuietly(String containerId) {
        if (containerId == null) {
            return true;
        }
        try {
            boolean removed = operations.remove(containerId);
            if (!removed) {
                log.warn("컨테이너를 제거하지 못했다 containerId={} — 누수다", containerId);
            }
            return removed;
        } catch (RuntimeException e) {
            log.warn("컨테이너 제거 중 오류 containerId={} — 누수다", containerId, e);
            return false;
        }
    }

    private ContainerOperations.ContainerCreation creationOf(SandboxCommand command) {
        return new ContainerOperations.ContainerCreation(
                command.image(),
                command.argv(),
                SandboxContainerSpec.environmentAsList(command),
                command.workspace().containerPath(),
                SandboxContainerSpec.hostConfig(command, properties),
                SandboxContainerSpec.labels(instanceId.value()));
    }
}
