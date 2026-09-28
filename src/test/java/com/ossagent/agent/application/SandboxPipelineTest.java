package com.ossagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.agent.domain.CodeSandbox;
import com.ossagent.agent.domain.ExecuteCommand;
import com.ossagent.agent.domain.FakeCodeSandbox;
import com.ossagent.agent.domain.SandboxCommand;
import com.ossagent.agent.domain.SandboxLimits;
import com.ossagent.agent.domain.SandboxPermanentException;
import com.ossagent.agent.domain.SandboxResult;
import com.ossagent.agent.domain.SandboxTransientException;
import com.ossagent.agent.domain.SandboxWorkspace;
import com.ossagent.agent.domain.SeedCacheCommand;
import com.ossagent.agent.domain.TargetCommandLine;
import com.ossagent.agent.domain.WarmCommand;
import com.ossagent.repository.domain.RepositoryCoordinates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 🔴 워밍 → 씨딩 → 실행의 <b>순서와 조합</b>을 고정한다 — #18 B4·B6 · Q-4 · S-3.
 *
 * <p>타입이 이미 막는 것(네트워크 열림 + 대상 명령)은 여기서 다시 세지 않는다.
 * 여기가 보는 것은 <b>타입이 표현할 수 없는 것</b>이다 — 순서 · 저장소당 1회 ·
 * 준비 실패 시 실행하지 않음.
 *
 * <p>⚠ 샌드박스 컨테이너를 띄우지 않는다 (S-3 · Q-9). 대역이 받은 명령을 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SandboxPipelineTest {

    private static final RepositoryCoordinates KAFKA =
            new RepositoryCoordinates("spring-projects", "spring-kafka");
    private static final RepositoryCoordinates BOOT =
            new RepositoryCoordinates("spring-projects", "spring-boot");
    private static final String JAVA_VERSION = "21";
    private static final String IMAGE = "eclipse-temurin:21-jdk";

    private final FakeCodeSandbox sandbox = new FakeCodeSandbox();

    @Test
    @DisplayName("🔴 워밍 → 씨딩 → 실행 순서로 돈다")
    void 세_단계가_순서대로_돈다(@TempDir Path root) throws Exception {
        pipeline().run(workspace(root), KAFKA, JAVA_VERSION, command());

        assertThat(sandbox.commands()).hasSize(3);
        assertThat(sandbox.commands().get(0)).isInstanceOf(WarmCommand.class);
        assertThat(sandbox.commands().get(1)).isInstanceOf(SeedCacheCommand.class);
        assertThat(sandbox.commands().get(2)).isInstanceOf(ExecuteCommand.class);
    }

    @Test
    @DisplayName("🔴 씨딩과 실행이 같은 볼륨을 쓰고, 실행에는 --offline 이 붙는다")
    void 볼륨과_오프라인_플래그(@TempDir Path root) throws Exception {
        pipeline().run(workspace(root), KAFKA, JAVA_VERSION, command());

        SeedCacheCommand seed = (SeedCacheCommand) sandbox.commands().get(1);
        ExecuteCommand execute = (ExecuteCommand) sandbox.commands().get(2);

        assertThat(execute.cacheVolume()).isEqualTo(seed.cacheVolume());
        assertThat(seed.cacheVolume().name()).contains("spring-kafka");
        // 🔴 붙지 않으면 network=none 에서 의존성 해석이 **타임아웃**으로 나타나
        //    30분 예산을 태우고 「테스트 실패」로 오분류된다
        assertThat(execute.argv()).contains("--offline");
        // 🔴 --no-daemon 이 없으면 단계마다 데몬을 띄우고 데몬 힙이 4GB 상한을 넘겨 137 로 죽어
        //    「코드가 틀렸다」로 기록된다 (#115)
        assertThat(execute.argv()).contains("--no-daemon");
    }

    @Test
    @DisplayName("🔴 같은 저장소의 두 번째 실행은 워밍하지 않는다 — B6")
    void 같은_저장소는_한_번만_워밍한다(@TempDir Path root) throws Exception {
        SandboxPipeline pipeline = pipeline();
        SandboxWorkspace workspace = workspace(root);

        pipeline.run(workspace, KAFKA, JAVA_VERSION, command());
        pipeline.run(workspace, KAFKA, JAVA_VERSION, command());

        assertThat(warmCount()).isEqualTo(1);
        assertThat(sandbox.commands()).hasSize(4);
    }

    /**
     * 🔴 볼륨 메모와 워크스페이스는 다른 사실이다 (#101). wrapper 배포본은 볼륨이 아니라
     * {@code <workspace>/.gradle} 에 있고 fetch 가 그 디렉토리를 통째로 지운다 — 볼륨 메모만 보고
     * 건너뛰면 새 워크스페이스가 network=none 에서 wrapper 를 받으려다 죽는다.
     */
    @Test
    @DisplayName("🔴 같은 저장소라도 새 워크스페이스면 다시 워밍한다 — wrapper 가 거기 있다 (#101)")
    void 새_워크스페이스는_다시_워밍한다(@TempDir Path root) throws Exception {
        SandboxPipeline pipeline = pipeline();
        Path real = root.toRealPath();
        SandboxWorkspace first = SandboxWorkspace.under(Files.createDirectories(real.resolve("ws1")), real);
        SandboxWorkspace second = SandboxWorkspace.under(Files.createDirectories(real.resolve("ws2")), real);

        pipeline.run(first, KAFKA, JAVA_VERSION, command());
        pipeline.run(second, KAFKA, JAVA_VERSION, command());

        assertThat(warmCount()).as("워크스페이스마다 워밍 1회").isEqualTo(2);
        assertThat(sandbox.commands().stream().filter(SeedCacheCommand.class::isInstance).count())
                .as("볼륨은 이미 씨딩됐으므로 다시 씨딩하지 않는다")
                .isEqualTo(1);
        assertThat(second.path().resolve(".gradle").resolve(SandboxPipeline.WARM_MARKER))
                .as("워밍 표식이 워크스페이스에 남는다 — fetch 가 지우면 다시 워밍한다")
                .exists();
    }

    @Test
    @DisplayName("재기동(새 파이프라인)에도 워밍 표식이 있으면 워밍은 건너뛰고 씨딩만 한다")
    void 재기동_뒤_표식이_있으면_씨딩만_한다(@TempDir Path root) throws Exception {
        SandboxWorkspace workspace = workspace(root);
        pipeline().run(workspace, KAFKA, JAVA_VERSION, command());
        sandbox.reset();

        pipeline().run(workspace, KAFKA, JAVA_VERSION, command());

        assertThat(warmCount()).isZero();
        assertThat(sandbox.commands().get(0)).isInstanceOf(SeedCacheCommand.class);
    }

    @Test
    @DisplayName("다른 저장소는 다시 워밍한다 — 캐시는 저장소별이다")
    void 다른_저장소는_다시_워밍한다(@TempDir Path root) throws Exception {
        SandboxPipeline pipeline = pipeline();
        SandboxWorkspace workspace = workspace(root);

        pipeline.run(workspace, KAFKA, JAVA_VERSION, command());
        pipeline.run(workspace, BOOT, JAVA_VERSION, command());

        assertThat(warmCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 워밍이 실패하면 실행하지 않는다 — 빈 캐시로 돌리면 「테스트 실패」로 오분류된다")
    void 워밍이_실패하면_실행하지_않는다(@TempDir Path root) throws Exception {
        sandbox.givenBuildFailure(1);

        assertThatThrownBy(() -> pipeline().run(workspace(root), KAFKA, JAVA_VERSION, command()))
                .isInstanceOf(SandboxPermanentException.class);

        assertThat(sandbox.commands()).hasSize(1);
        assertThat(sandbox.commands()).noneMatch(ExecuteCommand.class::isInstance);
    }

    @Test
    @DisplayName("준비가 타임아웃이면 다시 해 볼 수 있는 실패로 구분한다")
    void 준비_타임아웃은_일시적_실패다(@TempDir Path root) throws Exception {
        sandbox.givenTimeout();

        assertThatThrownBy(() -> pipeline().run(workspace(root), KAFKA, JAVA_VERSION, command()))
                .isInstanceOf(SandboxTransientException.class);
    }

    @Test
    @DisplayName("🔴 준비 실패 메시지에 빌드 출력을 싣지 않는다 — S-4")
    void 준비_실패_메시지에_출력을_싣지_않는다_S4(@TempDir Path root) throws Exception {
        sandbox.given(new SandboxResult(
                1, "ghp_THIS_LOOKS_LIKE_A_LEAKED_TOKEN 빌드 출력", false, false,
                Duration.ofSeconds(1), true));

        assertThatThrownBy(() -> pipeline().run(workspace(root), KAFKA, JAVA_VERSION, command()))
                .hasMessageNotContaining("ghp_")
                .hasMessageNotContaining("빌드 출력");
    }

    @Test
    @DisplayName("🔴 씨딩이 실패하면 준비됐다고 표시하지 않는다 — 다음 후보가 빈 캐시로 돈다")
    void 씨딩_실패는_기억하지_않는다(@TempDir Path root) throws Exception {
        SandboxWorkspace workspace = workspace(root);

        // 워밍은 성공하고 씨딩에서 실패하는 모양을 만든다
        FailOnSeed failing = new FailOnSeed();
        SandboxPipeline seedFailing = new SandboxPipeline(failing, IMAGE, limits(), limits());
        assertThatThrownBy(() -> seedFailing.run(workspace, KAFKA, JAVA_VERSION, command()))
                .isInstanceOf(SandboxPermanentException.class);

        assertThatThrownBy(() -> seedFailing.run(workspace, KAFKA, JAVA_VERSION, command()))
                .isInstanceOf(SandboxPermanentException.class);
        assertThat(failing.seeds)
                .as("🔴 실패를 「준비됨」으로 기억하면 다음 후보가 빈 캐시로 오프라인 실행을 한다")
                .isEqualTo(2);
        assertThat(failing.warms)
                .as("워밍은 성공했고 산출물이 워크스페이스에 있다 — 다시 워밍할 이유가 없다 (#101 표식)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 같은 저장소로 동시에 들어와도 워밍은 1건이다 — B6 락")
    void 동시_요청에도_워밍은_한_번이다(@TempDir Path root) throws Exception {
        SandboxPipeline pipeline = pipeline();
        SandboxWorkspace workspace = workspace(root);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    pipeline.run(workspace, KAFKA, JAVA_VERSION, command());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(warmCount())
                .as("🔴 워밍은 네트워크가 열린 채 임의 코드를 돌리는 단계다 — 중복은 비용이자 노출이다")
                .isEqualTo(1);
    }

    // ── 조립 ────────────────────────────────────────────────────────────────

    private SandboxPipeline pipeline() {
        return new SandboxPipeline(sandbox, IMAGE, limits(), limits());
    }

    private long warmCount() {
        return sandbox.commands().stream().filter(WarmCommand.class::isInstance).count();
    }

    private static TargetCommandLine command() {
        return TargetCommandLine.parse("./gradlew test");
    }

    private static SandboxLimits limits() {
        return new SandboxLimits(200_000L, 100_000L, 4L * 1024 * 1024 * 1024, 512L,
                Duration.ofMinutes(20));
    }

    private static SandboxWorkspace workspace(Path root) throws Exception {
        Path real = root.toRealPath();
        Path dir = real.resolve("ws");
        Files.createDirectories(dir);
        return SandboxWorkspace.under(dir, real);
    }

    /** 워밍은 성공하고 씨딩만 실패하는 대역 — {@link FakeCodeSandbox} 가 표현하지 못하는 조합이다 */
    private static final class FailOnSeed implements CodeSandbox {

        private int warms;
        private int seeds;

        @Override
        public SandboxResult run(SandboxCommand command) {
            if (command instanceof WarmCommand) {
                warms++;
                return new SandboxResult(0, "ok", false, false,
                        Duration.ofSeconds(1), true);
            }
            seeds++;
            return new SandboxResult(1, "cp failed", false, false,
                    Duration.ofSeconds(1), true);
        }
    }
}
