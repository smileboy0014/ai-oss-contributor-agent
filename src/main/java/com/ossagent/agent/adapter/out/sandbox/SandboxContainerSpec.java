package com.ossagent.agent.adapter.out.sandbox;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.ossagent.agent.domain.ExecuteCommand;
import com.ossagent.agent.domain.SandboxCommand;
import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.SeedCacheCommand;
import com.ossagent.agent.domain.WarmCommand;
import java.util.List;
import java.util.Map;

/**
 * 🔴 <b>격리 설정을 만드는 유일한 자리</b> — #17 · S-3.
 *
 * <h2>왜 한 곳인가</h2>
 *
 * <p>어댑터 여기저기에서 {@link HostConfig} 를 손보면 「어느 경로로는 네트워크가 열린다」가
 * 생긴다. 한 자리에 모으면 두 가지를 얻는다.
 *
 * <ol>
 *   <li><b>Docker 없이 전수 검증할 수 있다</b> — 순수 함수다. 데몬도, 컨테이너도 필요 없다.
 *       그것이 「S-3 관련 경로 커버리지 100%」를 말이 아니라 실제로 달성하는 방법이다</li>
 *   <li><b>빠뜨림이 컴파일 오류가 된다</b> — {@link SandboxCommand} 가 sealed 라
 *       switch 가 망라적이어야 한다. 네 번째 명령 타입이 생기면 <b>여기가 먼저 막힌다</b></li>
 * </ol>
 *
 * <h2>단계별 격리 — 표가 곧 구현이다</h2>
 *
 * <table border="1">
 *   <caption>세 단계</caption>
 *   <tr><th></th><th>네트워크</th><th>워크스페이스</th><th>캐시 볼륨</th><th>돌리는 것</th></tr>
 *   <tr><td>{@link WarmCommand}</td><td>전용 네트워크</td><td>RW</td>
 *       <td>🔴 <b>마운트 안 함</b></td><td>대상 저장소 빌드(우리 명령)</td></tr>
 *   <tr><td>{@link SeedCacheCommand}</td><td><b>none</b></td><td>RO</td><td>RW</td>
 *       <td>우리 {@code cp}</td></tr>
 *   <tr><td>{@link ExecuteCommand}</td><td><b>none</b></td><td>RW</td><td>RO</td>
 *       <td>대상 저장소 명령</td></tr>
 * </table>
 */
public final class SandboxContainerSpec {

    /** 네트워크 없음. Docker 의 예약어다. */
    static final String NO_NETWORK = "none";

    /** 누수 컨테이너를 나중에 찾기 위한 표시. 정리는 #26 의 몫이다. */
    static final String LABEL_SANDBOX = "oss-agent.sandbox";

    /**
     * 🔴 인스턴스 식별자를 함께 남긴다.
     *
     * <p>{@code sandbox=true} 만으로는 #26 이 「남의 컨테이너를 지워도 되는가」라는 같은
     * 딜레마를 다시 만난다. 다중 인스턴스에서 자기 것만 고를 수 있어야 한다.
     */
    static final String LABEL_INSTANCE = "oss-agent.instance";

    private SandboxContainerSpec() {
    }

    /**
     * 컨테이너 격리 설정.
     *
     * <p>⚠ 여기서 <b>하지 않는 것</b>도 계약이다.
     * <ul>
     *   <li>{@code autoRemove} — 종료와 동시에 지워져 <b>로그를 못 읽는다.</b>
     *       「정리 결과 로그」(FR-6)와 정면으로 충돌하고, {@code cleanedUp} 을 관측할
     *       주체도 사라진다. 삭제는 {@code finally} 에서 명시적으로 한다</li>
     *   <li>{@code readonlyRootfs} — 빌드가 {@code /tmp} 에 쓴다. 대신 워크스페이스 밖은
     *       컨테이너와 함께 사라지므로 지속 피해가 없다</li>
     *   <li>{@code user} — 비 root 실행은 바인드 소유권이 호스트 uid 와 엮이고
     *       macOS·Linux 가 다르게 동작한다. <b>알려진 공백</b>이고,
     *       {@code capDrop ALL} + {@code no-new-privileges} 가 그 자리를 부분적으로 메운다</li>
     * </ul>
     */
    public static HostConfig hostConfig(SandboxCommand command, SandboxProperties props) {
        var limits = command.limits();
        return HostConfig.newHostConfig()
                .withNetworkMode(networkMode(command, props))
                .withBinds(binds(command).toArray(new Bind[0]))
                .withCpuQuota(limits.cpuQuota())
                .withCpuPeriod(limits.cpuPeriod())
                .withMemory(limits.memoryBytes())
                // 🔴 주지 않으면 Docker 가 스왑을 memory 의 2배로 잡아
                //    선언한 상한이 상한이 아니게 된다.
                //    ⚠ 커널이 스왑 계정을 지원하지 않으면 Docker 가 경고만 하고 무시한다 —
                //      우리가 보장할 수 있는 것은 「설정에 그렇게 실렸다」까지다
                .withMemorySwap(limits.memorySwapBytes())
                // CPU·메모리만으로는 fork 폭탄을 막지 못한다. 호스트 PID 공간이 고갈된다
                .withPidsLimit(limits.pidsLimit())
                .withPrivileged(false)
                // 빌드에 커널 권한이 필요하지 않다
                .withCapDrop(com.github.dockerjava.api.model.Capability.ALL)
                // setuid 승격 차단
                .withSecurityOpts(List.of("no-new-privileges"));
    }

    /**
     * 🔴 네트워크는 <b>명령 타입이 정한다.</b> 설정 키로 뒤집을 수 없다.
     *
     * <p>{@code SANDBOX_NETWORK} 를 설정에서 없앤 이유가 이것이다 — 배포 설정 한 줄로
     * 실행 단계 격리가 꺼지면 안 된다. 워밍만 전용 네트워크를 쓰고, 그 이름도
     * {@link SandboxProperties} 가 {@code host}·{@code container:} 를 거부한 뒤의 값이다.
     */
    static String networkMode(SandboxCommand command, SandboxProperties props) {
        return switch (command) {
            case WarmCommand ignored -> props.warmNetwork();
            case SeedCacheCommand ignored -> NO_NETWORK;
            case ExecuteCommand ignored -> NO_NETWORK;
        };
    }

    /**
     * 🔴 바인드 목록을 만드는 <b>유일한 자리</b> — S-3 「작업 디렉토리만」.
     *
     * <p>워크스페이스는 {@link SandboxWorkspace} 가 이미 루트 하위임을 단언한 값이고,
     * 캐시는 {@code SandboxCacheVolume} 이 경로로 해석될 수 없음을 단언한 이름이다.
     * <b>여기서 새 경로를 만들어 내지 않는다</b> — 그것이 목록을 한 곳에 둔 이유다.
     */
    static List<Bind> binds(SandboxCommand command) {
        SandboxWorkspace workspace = command.workspace();
        Volume workspaceVolume = new Volume(workspace.containerPath());
        String source = workspace.path().toString();

        return switch (command) {
            // 🔴 워밍은 캐시 볼륨을 잡지 않는다. 신뢰할 수 없는 코드가 네트워크를 가진
            //    채로 도는 유일한 단계라, 볼륨에 쓸 수 있으면 오염이 후보 수명을 넘겨 남는다
            case WarmCommand ignored -> List.of(
                    new Bind(source, workspaceVolume, AccessMode.rw));

            // 볼륨에 쓰는 유일한 단계. 워크스페이스는 읽기만 하면 된다
            case SeedCacheCommand seed -> List.of(
                    new Bind(source, workspaceVolume, AccessMode.ro),
                    new Bind(seed.cacheVolume().name(),
                            new Volume(seed.cacheVolume().containerPath()), AccessMode.rw));

            // 🔴 캐시는 읽기전용이다. 캐시 공유는 「상태 재사용」이고, RO 가 아니면
            //    앞 실행의 산출물이 다음 판정을 오염시킨다
            case ExecuteCommand execute -> List.of(
                    new Bind(source, workspaceVolume, AccessMode.rw),
                    new Bind(execute.cacheVolume().name(),
                            new Volume(execute.cacheVolume().containerPath()), AccessMode.ro));
        };
    }

    /**
     * 🔴 컨테이너에 들어가는 환경변수 — <b>단계별 화이트리스트</b>. S-3 · S-4.
     *
     * <p>호스트 환경을 넘기지 않는다. {@link SandboxCommand} 에 환경변수를 받는 자리가
     * 없으므로 호출자가 추가할 통로도 없다.
     *
     * <p>⚠ {@code GRADLE_USER_HOME} 이 <b>워크스페이스 안</b>인 것이 핵심이다.
     * wrapper 배포본이 거기 쌓이고, 워밍과 실행이 같은 워크스페이스를 쓰므로 실행 단계가
     * 그것을 재사용한다 — wrapper 부트스트랩은 Gradle 이 시작되기 <b>전</b>이라
     * {@code --offline} 이 닿지 않는다.
     */
    static Map<String, String> environment(SandboxCommand command) {
        SandboxWorkspace workspace = command.workspace();
        return switch (command) {
            case WarmCommand ignored ->
                    Map.of("GRADLE_USER_HOME", workspace.containerGradleHome());

            // cp 에는 아무것도 필요 없다
            case SeedCacheCommand ignored -> Map.of();

            // GRADLE_RO_DEP_CACHE 는 modules-2 를 「담고 있는」 디렉토리를 가리킨다.
            // 씨딩이 볼륨 루트 바로 아래에 modules-2 를 두므로 볼륨 루트가 곧 그것이다
            case ExecuteCommand execute -> Map.of(
                    "GRADLE_USER_HOME", workspace.containerGradleHome(),
                    "GRADLE_RO_DEP_CACHE", execute.cacheVolume().containerPath());
        };
    }

    /** {@code KEY=VALUE} 형태 — docker-java 가 요구하는 모양. */
    static List<String> environmentAsList(SandboxCommand command) {
        return environment(command).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .sorted()   // 순서를 고정해 테스트가 재현 가능하게 한다
                .toList();
    }

    static Map<String, String> labels(String instanceId) {
        return Map.of(LABEL_SANDBOX, "true", LABEL_INSTANCE, instanceId);
    }
}
