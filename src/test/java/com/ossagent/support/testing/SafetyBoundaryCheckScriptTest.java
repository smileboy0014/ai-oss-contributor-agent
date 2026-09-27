package com.ossagent.support.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 안전 경계 정적 검사 스크립트를 <b>실제로 실행해</b> 검사한다 — S-1~S-3 · 이슈 #75.
 *
 * <h2>왜 이 테스트가 생겼나</h2>
 * {@code safety-boundary-check.sh} 에는 <b>테스트가 하나도 없었다.</b> #75 가 그 스크립트에도
 * {@code export LC_ALL=C} 를 넣었는데, 그것이 지워져도 <b>아무도 모르는</b> 상태였다 —
 * 그 파일 주석이 스스로 자인한 잔여 위험이고, 리뷰가 「자가 점검이 아니라 테스트로 닫으라」고
 * 지적한 자리다.
 *
 * <p>{@code secret-scan.sh} 쪽과 달리 여기에는 <b>기동 자가 점검을 넣지 않았다</b> —
 * 조용한 0건의 결과가 회수 불가한 유출이 아니라 <b>보조 층</b>의 신호 하나를 잃는 것이고,
 * 점검을 더하면 커밋을 막는 오탐 표면만 두 배가 되기 때문이다. 그 판단의 대가가
 * 「지워져도 모른다」였고, 이 테스트가 그 대가를 갚는다.
 *
 * <h2>무엇이 새던가</h2>
 * UTF-8 로케일의 grep 은 유효하지 않은 바이트를 만나면 <b>그 뒤쪽을 매칭에서 버린다.</b>
 * 그래서 한 줄 안에서 부정 바이트 <b>뒤에</b> 오는 {@code ProcessBuilder} 가 조용히 통과했다.
 * 이 스크립트의 위반 패턴은 전부 <b>비앵커</b>라 구멍에 그대로 노출된다.
 *
 * <h2>격리</h2>
 * 실제 저장소를 건드리지 않는다. 임시 디렉토리에 git 저장소를 새로 만들고 스크립트를
 * 복사해 돌린다 — {@code SecretScanScriptTest} 와 같은 수법이고, 그쪽 javadoc 이
 * {@code XDG_CONFIG_HOME} 까지 닫아야 하는 이유를 적어 두었다.
 */
class SafetyBoundaryCheckScriptTest {

    private static final Path SCRIPT = Path.of(".claude/scripts/safety-boundary-check.sh");

    /** latin-1 {@code é}. 단독으로는 유효한 UTF-8 이 아니다. 🔴 <b>매치 대상 앞</b>에 둬야 샌다. */
    private static final byte INVALID_UTF8 = (byte) 0xE9;

    /**
     * ⚠️ 런타임 조립이다. 소스에 {@code new ProcessBuilder} 를 그대로 쓰면
     * <b>이 파일이 커밋되지 않는다</b> — 바로 그 게이트가 잡는 모양이기 때문이다.
     *
     * <p>{@code SecretScanScriptTest} 의 {@code KEY_BLOCK} 이 같은 이유로 {@code String.join}
     * 을 쓴다. 스크립트 주석이 예고한 대로 <b>화이트리스트를 되살리지 말고 예시 쪽을 고친다.</b>
     * 조립하면 소스 텍스트가 {@code new "} 로 끊겨 {@code new[[:space:]]+ProcessBuilder} 에
     * 물리지 않는다.
     */
    private static final String VIOLATION = "new " + "ProcessBuilder";

    /** 부정 바이트가 <b>위반 앞</b>에 오는 한 줄. 구멍이 살아 있으면 이 줄이 통째로 안 보인다. */
    private static final String HIDDEN_VIOLATION_HEAD = "        String note = \"caf";

    private static final String HIDDEN_VIOLATION_TAIL =
            "\"; Process p = " + VIOLATION + "(\"x\").start();\n";

    @Test
    @DisplayName("🔴 양성 대조 — 이 환경에서 구멍이 실제로 재현된다")
    void 물림의_전제가_이_환경에서_성립한다_S3(@TempDir Path repo) throws Exception {
        // 🔴 「초록의 원인이 둘이다」를 가른다. 로케일 고정을 제거한 사본을 UTF-8 로케일로
        //    돌려 **통과**가 나와야, 아래 회귀의 「차단」이 수정의 공로가 된다.
        writeProbe(repo);

        ScanResult unguarded = scan(repo, script -> script.replace("\nexport LC_ALL=C\n", "\n"),
                UTF8_LOCALE);

        assumeTrue(!unguarded.blocked(), """
                이 실행 환경에서는 #75 의 구멍이 재현되지 않는다 — 아래 회귀는 물림을
                증명하지 못하고 「원래 잡던 것을 계속 잡는다」까지만 본다. 출력:
                %s""".formatted(unguarded.output()));

        assertThat(unguarded.output())
                .as("구멍이 재현됐다면 검사는 **수행되고 0건**을 냈어야 한다 — 파일이 빠진 것이 아니다")
                .contains("안전 경계 검사 통과");
    }

    @Test
    @DisplayName("부정 바이트 뒤에 숨은 ProcessBuilder 도 차단된다")
    void 같은_줄의_비UTF8_바이트가_호스트실행을_가리지_못한다_S3(@TempDir Path repo) throws Exception {
        writeProbe(repo);

        ScanResult result = scan(repo, Function.identity(), UTF8_LOCALE);

        assertThat(result.blocked())
                .as("""
                        한 줄 안에서 부정 바이트 뒤에 오는 호스트 실행이 조용히 통과하면,
                        신뢰할 수 없는 대상 저장소 코드를 호스트에서 돌리는 경로가 열린다. 출력:
                        %s""", result.output())
                .isTrue();
        assertThat(result.output()).contains("S-3");
    }

    @Test
    @DisplayName("비-UTF-8 바이트가 든 정상 파일은 통과한다")
    void 비UTF8_바이트만으로는_차단하지_않는다(@TempDir Path repo) throws Exception {
        // 과차단하면 사람이 게이트를 끄고 싶어진다. latin-1 로 저장된 옛 파일은 정상이다.
        writeJava(repo, ("class Probe {\n    String note = \"caf").getBytes(StandardCharsets.UTF_8),
                ("\";\n}\n").getBytes(StandardCharsets.UTF_8));

        assertThat(scan(repo, Function.identity(), UTF8_LOCALE).blocked())
                .as("넓힌 판정이 과차단으로 뒤집히면 수정이 아니라 다른 고장이다")
                .isFalse();
    }

    @Test
    @DisplayName("upstream 좌표로 가는 REST 쓰기를 차단한다")
    void upstream_으로_가는_REST_쓰기를_차단한다_S1(@TempDir Path repo) throws Exception {
        // #22 이후 쓰기 경로는 JGit 이 아니라 REST 다. setRemote·push() 패턴은 이 모양을
        // 전혀 보지 못하므로 패턴을 하나 더 뒀고, 그것이 실제로 무는지 여기서 본다.
        writeJava(repo, ("class Probe {\n    void go() {\n        client."
                        + "post" + "(upstream, \"git/refs\", body);\n    }\n").getBytes(StandardCharsets.UTF_8),
                ("}\n").getBytes(StandardCharsets.UTF_8));

        ScanResult result = scan(repo, Function.identity(), Map.of());

        assertThat(result.blocked())
                .as("출력: %s", result.output())
                .isTrue();
        assertThat(result.output()).contains("S-1");
    }

    @Test
    @DisplayName("쓰기 대상이 Fork 면 하위 경로에 upstream 이 있어도 통과한다")
    void 엔드포인트_이름의_upstream은_과차단하지_않는다_S1(@TempDir Path repo) throws Exception {
        // 🔴 merge-upstream 은 GitHub 엔드포인트 이름이고 쓰기 대상은 Fork 다.
        //    실측에서 걸렸고, safety-ok 로 덮는 대신 패턴을 첫 인자로 좁혔다 —
        //    과차단으로 죽는 게이트는 반드시 꺼진다.
        writeJava(repo, ("class Probe {\n    void go() {\n        client."
                        + "post" + "(fork, \"merge-upstream\", body);\n    }\n").getBytes(StandardCharsets.UTF_8),
                ("}\n").getBytes(StandardCharsets.UTF_8));

        ScanResult result = scan(repo, Function.identity(), Map.of());

        assertThat(result.blocked())
                .as("출력: %s", result.output())
                .isFalse();
    }

    @Test
    @DisplayName("스크립트를 실제로 실행했다")
    void 스크립트를_실제로_실행했다(@TempDir Path repo) throws Exception {
        // 🔴 0건 통과 방지. 이 스크립트는 대상 java 파일이 없으면 조용히 exit 0 이라,
        //    위 검사들이 「실행되지 않아서」 초록일 수 있다.
        writeJava(repo, "class Probe {\n}\n".getBytes(StandardCharsets.UTF_8), new byte[0]);

        assertThat(scan(repo, Function.identity(), Map.of()).output())
                .as("검사를 수행했다는 증거가 출력에 없다 — 대상 파일이 0건이었을 수 있다")
                .contains("안전 경계 검사 통과");
    }

    // ── 실행 ──────────────────────────────────────────────────────────────────

    /**
     * 상속 로케일을 UTF-8 로 고정한다 — 스크립트의 {@code export} 가 <b>물려받은 값을
     * 이기는지</b> 보기 위해서다. 이 로케일이 없는 환경에서는 {@code C} 로 폴백해 구멍이
     * 재현되지 않고, 그때는 위 양성 대조가 <b>명시적으로 skip</b> 한다.
     */
    private static final Map<String, String> UTF8_LOCALE =
            Map.of("LC_ALL", "C.UTF-8", "LANG", "C.UTF-8");

    private record ScanResult(int exitCode, String output) {
        boolean blocked() {
            return exitCode != 0;
        }
    }

    private static void writeProbe(Path repo) throws IOException {
        writeJava(repo,
                ("class Probe {\n" + HIDDEN_VIOLATION_HEAD).getBytes(StandardCharsets.UTF_8),
                (HIDDEN_VIOLATION_TAIL + "}\n").getBytes(StandardCharsets.UTF_8));
    }

    /** {@code head} + 부정 바이트 1개 + {@code tail} 을 {@code src/main/java/Probe.java} 에 쓴다. */
    private static void writeJava(Path repo, byte[] head, byte[] tail) throws IOException {
        Path file = repo.resolve("src/main/java/Probe.java");
        Files.createDirectories(file.getParent());
        byte[] all = new byte[head.length + 1 + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        all[head.length] = INVALID_UTF8;
        System.arraycopy(tail, 0, all, head.length + 1, tail.length);
        Files.write(file, all);
    }

    private static ScanResult scan(Path repo, Function<String, String> mutate,
            Map<String, String> extraEnv) throws Exception {
        setUp(repo, "git", "init", "-q");
        setUp(repo, "git", "config", "user.email", "test@example.com");
        setUp(repo, "git", "config", "user.name", "test");

        Path scripts = repo.resolve(".claude/scripts");
        Files.createDirectories(scripts);
        Path copied = scripts.resolve("safety-boundary-check.sh");
        Files.writeString(copied, mutate.apply(Files.readString(SCRIPT, StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        setUp(repo, "git", "add", "src/main/java/Probe.java");
        return run(repo, extraEnv, "bash", copied.toString());
    }

    /** 준비 명령. <b>실패하면 즉시 터뜨린다</b> — 그냥 넘기면 검사가 공허하게 통과한다. */
    private static void setUp(Path workingDir, String... command) throws Exception {
        ScanResult result = run(workingDir, Map.of(), command);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("테스트 준비가 실패했습니다: " + String.join(" ", command)
                    + " (exit=" + result.exitCode() + ")\n" + result.output());
        }
    }

    /**
     * ⚠️ 여기서 돌리는 것은 <b>우리 저장소의 하네스 스크립트</b>라 S-3 의 보호 대상이 아니다 —
     * {@code testing-philosophy.md} 가 그렇게 정해 뒀다.
     *
     * <p>출력을 파일로 받는 이유는 {@code SecretScanScriptTest} 와 같다 — 스트림을 먼저 다
     * 읽으면 타임아웃이 무효가 되고, 순서를 뒤집으면 파이프 버퍼가 차서 교착이다.
     */
    private static ScanResult run(Path workingDir, Map<String, String> extraEnv, String... command)
            throws Exception {
        Path log = Files.createTempFile("safety-boundary-out", ".log");
        // 🔴 try 밖에 선언해 finally 가 반드시 데려가게 한다 — 아래 finally 주석
        Process process = null;
        try {
            // 🕳 사유는 한 줄이어야 한다 — 훅은 위반 라인의 「바로 윗줄」만 본다
            // safety-ok: 임시 디렉토리에서 git 플러밍과 우리 저장소의 .claude/scripts 만 돌린다. 대상 저장소 코드가 아니라 S-3 대상이 아니다
            ProcessBuilder builder = new ProcessBuilder(List.of(command))
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            isolateGitConfig(builder.environment(), workingDir);
            builder.environment().putAll(extraEnv);

            process = builder.start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("스크립트가 끝나지 않았습니다: " + String.join(" ", command));
            }
            // 🔴 readString 을 쓰지 않는다 — MalformedInputException 으로 터진다.
            //   이 스크립트는 위반 라인의 **원문**을 출력에 싣고, 이 테스트의 표본에는
            //   유효하지 않은 바이트가 들어 있다. 실제로 그렇게 터졌다.
            //   new String(bytes, UTF_8) 은 예외 대신 U+FFFD 로 치환한다.
            return new ScanResult(process.exitValue(),
                    new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        } finally {
            // 🔴 타임아웃 경로에만 두면 샌다. JVM 이 죽거나 이 스레드가 인터럽트되면
            //    (Gradle 이 걸려 누가 죽일 때가 정확히 그렇다) waitFor 가 예외로 빠져나가고
            //    자식 셸은 **JVM 과 함께 죽지 않는다** — 고아가 된다.
            //    2026-09-27 에 같은 모양의 고아 8개가 1일 21시간째 도는 것을 발견했다.
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(log);
        }
    }

    /**
     * 임시 저장소가 <b>바깥 설정을 하나도 보지 않게</b> 한다.
     *
     * <p>🔴 {@code HOME} 만 옮기는 것으로는 부족하다 — {@code XDG_CONFIG_HOME} 이 설정돼 있으면
     * git 이 그쪽 설정을 계속 읽고, 거기 {@code core.hooksPath} 가 있으면 <b>이 테스트가
     * 개발자의 훅을 실행</b>하게 된다. {@code SecretScanScriptTest} 가 실측으로 확인한 것이다.
     */
    private static void isolateGitConfig(Map<String, String> env, Path workingDir) {
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("HOME", workingDir.toString());
        env.remove("XDG_CONFIG_HOME");
    }
}
