package com.ossagent.agent.adapter.out.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.agent.domain.BuildTool;
import com.ossagent.agent.domain.ExecuteCommand;
import com.ossagent.agent.domain.SandboxCacheVolume;
import com.ossagent.agent.domain.SandboxResult;
import com.ossagent.agent.domain.SandboxTransientException;
import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.WarmCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 컨테이너 <b>수명주기</b>를 고정한다 — #17 · S-3.
 *
 * <p>여기서 증명하는 것은 격리 설정이 아니라(그쪽은 {@link SandboxContainerSpecTest})
 * <b>「어떤 경로로 끝나든 컨테이너가 남지 않는가」</b>다. 능력 페이크로는 증명되지 않는
 * 성질이라 docker 제어 호출을 기록하는 대역을 쓴다.
 */
class DockerCodeSandboxTest {

    @TempDir
    Path root;

    private RecordingContainerOperations operations;
    private DockerCodeSandbox sandbox;
    private SandboxProperties props;
    private SandboxWorkspace workspace;
    private SandboxCacheVolume cache;

    @BeforeEach
    void setUp() throws IOException {
        operations = new RecordingContainerOperations();
        props = new SandboxProperties(root, null, null, null, null, null, null, null, null, null, null);
        workspace = SandboxWorkspace.under(
                Files.createDirectories(root.resolve("candidate-1")), props.workspaceRoot());
        cache = SandboxCacheVolume.forRepository("spring-projects", "spring-kafka");
        sandbox = new DockerCodeSandbox(operations, props, new SandboxInstanceId("instance-a"),
                Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC));
    }

    // ─────────────────────────────────────────────────────────
    // 정리 — 어떤 경로로 끝나든 남지 않는다
    // ─────────────────────────────────────────────────────────

    @Test
    void 정상_종료_후_컨테이너를_제거한다_S3() {
        SandboxResult result = sandbox.run(execute());

        assertThat(operations.calls()).containsExactly("create", "start", "await", "logs", "remove");
        assertThat(result.cleanedUp()).isTrue();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void 타임아웃이면_kill_한_뒤에_제거한다_S3() {
        operations.thenTimeout();

        SandboxResult result = sandbox.run(execute());

        assertThat(operations.calls())
                .as("""
                        순서가 검증 대상이다. 「kill 과 remove 가 둘 다 불렸다」로는 부족하다 —
                        살아 있는 컨테이너를 지우려 들면 데몬이 거부하거나 강제 종료가 되고,
                        그러면 「정리했다」는 보고가 사실과 달라진다.""")
                .containsExactly("create", "start", "await", "kill", "logs", "remove");
        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
        assertThat(result.cleanedUp()).isTrue();
    }

    @Test
    void 시작이_예외로_끝나도_제거한다_S3() {
        operations.thenStartThrows(new SandboxTransientException("데몬 장애"));

        assertThatThrownBy(() -> sandbox.run(execute()))
                .isInstanceOf(SandboxTransientException.class);

        assertThat(operations.calls())
                .as("예외 경로에서 제거가 빠지면 실패할 때마다 컨테이너가 쌓인다")
                .containsExactly("create", "start", "remove");
    }

    @Test
    void 로그를_못_받아도_제거하고_실행_결과를_지킨다_S3() {
        operations.thenExitWith(1).thenLogsThrows(new SandboxTransientException("로그 조회 실패"));

        SandboxResult result = sandbox.run(execute());

        assertThat(operations.calls()).endsWith("remove");
        assertThat(result.exitCode())
                .as("""
                        로그를 못 받았다고 실행 결과를 버리면 exitCode·timedOut·cleanedUp 이
                        통째로 사라진다. 실행은 이미 끝났는데 그 사실을 호출자가 알 수 없게 된다 —
                        「빌드 실패는 예외가 아니라 결과다」와 어긋난다.""")
                .isEqualTo(1);
        assertThat(result.cleanedUp()).isTrue();
        assertThat(result.truncated())
                .as("로그가 없다는 사실은 truncated 로 하류에 전달한다")
                .isTrue();
    }

    @Test
    void 컨테이너를_만들지_못했으면_제거를_시도하지_않는다_S3() {
        operations.thenCreateThrows(new SandboxTransientException("이미지가 없다"));

        assertThatThrownBy(() -> sandbox.run(execute()))
                .isInstanceOf(SandboxTransientException.class);

        assertThat(operations.calls())
                .as("만들지 못했으면 남은 것도 없다 — 없는 것을 지우려다 나는 오류가 진단을 흐린다")
                .containsExactly("create");
    }

    @Test
    void 제거_실패를_결과와_로그에_남긴다_S3() {
        operations.thenRemoveFails();

        SandboxResult result = sandbox.run(execute());

        assertThat(result.cleanedUp())
                .as("조용히 넘기면 컨테이너가 쌓이고, 그것은 시간이 지나야 드러나는 고장이다")
                .isFalse();
        assertThat(result.succeeded())
                .as("정리 실패가 실행 결과를 뒤집지 않는다 — 별개의 사실이다")
                .isTrue();
    }

    @Test
    void 제거가_예외를_던져도_원래_결과를_잃지_않는다_S3() {
        operations.thenRemoveThrows(new IllegalStateException("데몬 응답 없음"));

        SandboxResult result = sandbox.run(execute());

        assertThat(result.cleanedUp()).isFalse();
        assertThat(result.exitCode())
                .as("finally 안에서 예외가 나가면 본래 실패를 덮어써 진단이 엉뚱해진다")
                .isZero();
    }

    // ─────────────────────────────────────────────────────────
    // 결과
    // ─────────────────────────────────────────────────────────

    @Test
    void 빌드_실패는_예외가_아니라_종료코드다() {
        operations.thenExitWith(1);

        SandboxResult result = sandbox.run(execute());

        assertThat(result.exitCode())
                .as("0 이 아닌 종료코드는 게이트가 작동한 것이지 오류가 아니다. "
                        + "예외로 내보내면 호출자가 재시도 루프에서 삼킨다")
                .isEqualTo(1);
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void 출력이_잘렸으면_사실을_결과에_남긴다() {
        operations.thenLogs("앞부분만", true);

        SandboxResult result = sandbox.run(execute());

        assertThat(result.truncated()).isTrue();
        assertThat(result.outputIsComplete())
                .as("잘린 로그를 LLM 이 「전부」로 읽고 「테스트가 통과했다」로 판단하면 "
                        + "게이트가 무력해진다")
                .isFalse();
    }

    @Test
    void 결과를_찍어도_대상_저장소_출력이_나오지_않는다_S4() {
        operations.thenLogs("token " + "ghp_" + "A".repeat(36), false);

        SandboxResult result = sandbox.run(execute());

        assertThat(result.toString())
                .as("빌드 출력에는 대상 저장소가 커밋해 둔 시크릿이 섞여 있을 수 있다 — "
                        + "로그로 나가면 회수할 수 없다")
                .doesNotContain("ghp_", "token");
    }

    // ─────────────────────────────────────────────────────────
    // 컨테이너 생성 인자
    // ─────────────────────────────────────────────────────────

    @Test
    void 워밍만_전용_네트워크를_보장한다_S3() {
        sandbox.run(warm());
        assertThat(operations.ensuredNetworks()).containsExactly("oss-agent-warm");

        RecordingContainerOperations other = new RecordingContainerOperations();
        new DockerCodeSandbox(other, props, new SandboxInstanceId("instance-a"), Clock.systemUTC()).run(execute());

        assertThat(other.calls())
                .as("실행 단계는 네트워크가 none 이라 보장할 네트워크가 없다")
                .doesNotContain("ensureNetwork");
    }

    @Test
    void 누수_컨테이너를_찾을_수_있게_라벨을_단다() {
        sandbox.run(execute());

        assertThat(operations.creation().labels())
                .containsEntry("oss-agent.sandbox", "true")
                .containsEntry("oss-agent.instance", "instance-a");
    }

    @Test
    void 작업_디렉토리는_워크스페이스_마운트_지점이다() {
        sandbox.run(execute());

        assertThat(operations.creation().workingDir()).isEqualTo("/workspace");
    }

    @Test
    void 컨테이너_환경변수가_화이트리스트_뿐이다_S3_S4() {
        sandbox.run(execute());

        assertThat(operations.creation().env())
                .as("호스트 환경을 넘기지 않는다. 호출자가 추가할 통로도 없다")
                .containsExactly(
                        "GRADLE_RO_DEP_CACHE=/dependency-cache",
                        "GRADLE_USER_HOME=/workspace/.gradle");
    }

    // ─────────────────────────────────────────────────────────

    private WarmCommand warm() {
        return WarmCommand.of(workspace, BuildTool.GRADLE, "21",
                props.defaultImage(), props.warmLimits());
    }

    private ExecuteCommand execute() {
        return ExecuteCommand.of(workspace, cache, BuildTool.GRADLE, "21",
                props.defaultImage(), List.of("./gradlew", "test"), props.executeLimits());
    }
}
