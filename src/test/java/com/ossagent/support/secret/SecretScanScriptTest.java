package com.ossagent.support.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 커밋 차단 스크립트를 <b>실제로 실행해</b> 검사한다 — S-4 · 이슈 #64.
 *
 * <h2>왜 드리프트 테스트로는 안 되나</h2>
 * {@link SecretPatternDriftTest} 는 스크립트에서 <b>정규식만</b> 뽑아 Java 로 컴파일한다.
 * 그런데 BOM 처리는 정규식이 아니라 {@code read_file} 의 파이프에 있다 —
 * <b>그 테스트가 원리적으로 볼 수 없는 자리</b>다. 정규식을 아무리 대조해도
 * {@code strip_bom} 이 빠진 것은 드러나지 않는다.
 *
 * <h2>🔴 이 테스트의 진짜 값어치 — 환경 차이를 잡는다</h2>
 * #64 는 <b>개발 머신의 {@code grep} 이 {@code ugrep} 이라 구멍을 가리고 있던 것</b>에서
 * 시작했다. 프로브가 차단되길래 이슈가 틀린 줄 알았는데, {@code /usr/bin/grep}(BSD)으로
 * 돌리니 그대로 샜다. CI 의 GNU grep 도 샌다.
 *
 * <p>이 테스트는 <b>그 환경의 {@code grep}·{@code sed}·{@code bash} 로 실제 실행</b>한다.
 * 그래서 「내 머신에서 초록」이 아니라 <b>「이 실행 환경에서 초록」</b>을 증명한다.
 * CI 가 다른 구현을 쓰면 CI 에서 빨개진다 — 그것이 필요한 신호다.
 *
 * <h2>격리</h2>
 * 실제 저장소를 건드리지 않는다. 임시 디렉토리에 git 저장소를 새로 만들고 스크립트를
 * 복사해 돌린다 — 스크립트가 {@code git rev-parse --show-toplevel} 로 루트를 찾기 때문에
 * 저장소 밖에서는 조용히 {@code exit 0} 이 된다(그 자체가 이 테스트의 함정이라
 * {@link #스크립트를_실제로_실행했다()} 가 그것을 막는다).
 */
class SecretScanScriptTest {

    private static final Path SCRIPT = Path.of(".claude/scripts/secret-scan.sh");

    /** UTF-8 BOM. 이 3바이트가 줄 시작을 차지해 개인키 헤더 검사를 통과시켰다. */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * ⚠️ 텍스트 블록으로 쓰면 <b>이 파일이 커밋되지 않는다.</b> 헤더가 줄 시작에 오고,
     * 그게 바로 이 게이트가 잡는 모양이기 때문이다 — 실제로 한 번 막혔다.
     *
     * <p>{@code secret-scan.sh} 의 주석이 그 경우를 예고하며 <b>「화이트리스트를 되살리지
     * 말고 예시 쪽을 고쳐라」</b>라고 적어 뒀다(#58). 그대로 한다 — {@code String.join} 이면
     * 소스의 각 줄이 {@code "} 로 시작해 걸리지 않는다.
     */
    private static final String KEY_BLOCK = String.join("\n",
            "-----BEGIN RSA PRIVATE KEY-----",
            "NOTAREALKEYFORTESTSONLYNOTAREALKEY",
            "-----END RSA PRIVATE KEY-----",
            "");

    @Test
    @DisplayName("BOM 이 붙은 개인키 파일이 차단된다")
    void BOM_이_붙은_개인키가_차단된다_S4(@TempDir Path repo) throws Exception {
        writeWithBom(repo.resolve("key.txt"), KEY_BLOCK);

        ScanResult result = scan(repo, "key.txt");

        assertThat(result.blocked())
                .as("""
                        BOM 3바이트가 줄 시작을 차지하면 헤더 검사가 통과한다 — .pem 은 헤더가
                        1행이라 파일 전체가 샌다. PowerShell 5.1 의 Out-File 기본값이 UTF-8 BOM 이다.
                        출력:
                        %s""", result.output())
                .isTrue();
        assertThat(result.output()).contains("Private Key");
    }

    @Test
    @DisplayName("BOM 이 없는 개인키는 계속 차단된다")
    void BOM_이_없는_개인키도_차단된다_S4(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("key.txt"), KEY_BLOCK);

        assertThat(scan(repo, "key.txt").blocked())
                .as("BOM 처리를 넣으면서 원래 잡던 것을 놓치면 안 된다")
                .isTrue();
    }

    @Test
    @DisplayName("BOM 이 붙은 정상 파일은 통과한다")
    void BOM_이_붙은_정상_파일은_통과한다(@TempDir Path repo) throws Exception {
        // BOM 자체를 위험 신호로 다루지 않는다 — 벗겨서 내용으로 판단할 뿐이다
        writeWithBom(repo.resolve("readme.md"), "# 배포 메모\n키는 Secret Manager 에 둔다.\n");

        assertThat(scan(repo, "readme.md").blocked())
                .as("과차단하면 사람이 게이트를 끄고 싶어진다")
                .isFalse();
    }

    @Test
    @DisplayName("BOM 처리가 고장나면 조용히 통과하지 않고 멈춘다")
    void 전처리가_고장나면_시끄럽게_멈춘다_S4(@TempDir Path repo) throws Exception {
        // 🔴 read_file 이 「… | strip_bom」 으로 끝나므로 그 sed 가 실패하면 파이프가
        //    빈 출력을 내고 **모든 패턴이 히트 0건**이 된다 — 게이트가 「✅ 통과」를
        //    찍으며 진짜 개인키를 커밋시킨다. 실측으로 재현했다.
        //
        //    ⚠ 이 스크립트가 고치려던 것과 같은 종류의 실패다. 구멍을 닫으면서
        //    「조용히 꺼지는 게이트」를 새로 만들 뻔했다.
        Files.writeString(repo.resolve("key.txt"), KEY_BLOCK);

        ScanResult result = scanWithBrokenPreprocessor(repo, "key.txt");

        assertThat(result.blocked())
                .as("""
                        전처리가 고장났는데 exit 0 이면 시크릿이 그대로 커밋된다.
                        게이트는 고장났을 때 **통과가 아니라 중단**이어야 한다.
                        출력:
                        %s""", result.output())
                .isTrue();
        assertThat(result.output())
                .as("무엇이 고장났는지 말해야 사람이 고칠 수 있다")
                .contains("게이트가 고장났습니다");
    }

    @Test
    @DisplayName("스크립트를 실제로 실행했다")
    void 스크립트를_실제로_실행했다(@TempDir Path repo) throws Exception {
        // 🔴 0건 통과 방지. 스크립트는 git 저장소 밖이거나 대상 파일이 없으면
        //    조용히 exit 0 이다 — 위 세 검사가 「실행되지 않아서」 초록일 수 있다.
        Files.writeString(repo.resolve("readme.md"), "본문\n");

        ScanResult result = scan(repo, "readme.md");

        assertThat(result.output())
                .as("스크립트가 검사를 수행했다는 증거가 출력에 없다 — 저장소 밖에서 돌았을 수 있다")
                .contains("시크릿 검사 통과");
        assertThat(result.output())
                .as("어느 grep 으로 돌았는지 남아야 한다 — #64 가 그것 때문에 구멍을 못 볼 뻔했다")
                .contains("grep:");
    }

    // ── 실행 ──────────────────────────────────────────────────────────────────

    private record ScanResult(int exitCode, String output) {
        boolean blocked() {
            return exitCode != 0;
        }
    }

    /**
     * 임시 git 저장소를 만들고 그 안에서 스크립트를 돌린다.
     *
     * <p>{@code SCAN_MODE=staged} 로 돈다 — <b>git 훅이 실제로 쓰는 경로</b>이고
     * {@code git show :파일} 로 읽으므로 인덱스에 들어간 바이트를 그대로 본다.
     */
    private static ScanResult scan(Path repo, String target) throws Exception {
        return scan(repo, target, Function.identity());
    }

    /**
     * BOM 전처리를 고장낸 사본으로 돌린다 — {@code sed} 부재·로케일 거부를 재현한다.
     *
     * <p>스크립트를 <b>복사본에서만</b> 고친다. 원본은 건드리지 않는다.
     */
    private static ScanResult scanWithBrokenPreprocessor(Path repo, String target)
            throws Exception {
        return scan(repo, target, script -> script.replace("LC_ALL=C sed ", "LC_ALL=C no_such_cmd "));
    }

    private static ScanResult scan(Path repo, String target, Function<String, String> mutate)
            throws Exception {
        setUp(repo, "git", "init", "-q");
        setUp(repo, "git", "config", "user.email", "test@example.com");
        setUp(repo, "git", "config", "user.name", "test");

        Path scripts = repo.resolve(".claude/scripts");
        Files.createDirectories(scripts);
        Path copied = scripts.resolve("secret-scan.sh");
        Files.writeString(copied, mutate.apply(Files.readString(SCRIPT, StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        setUp(repo, "git", "add", target);
        return run(repo, "bash", copied.toString());
    }

    /**
     * 준비 명령. <b>실패하면 즉시 터뜨린다</b> — 그냥 넘기면 검사가 공허하게 통과한다.
     *
     * <p>이 스크립트는 git 저장소 밖이면 <b>출력 없이 {@code exit 0}</b> 이다. 그래서
     * 준비가 깨지면 「차단되지 않음」을 기대하는 검사가 <b>검사가 돌지 않아서</b> 초록이
     * 된다 — {@code testing-philosophy.md} 의 「0건 검사로 통과하지 않게 한다」가 가리키는
     * 바로 그 모양이고, {@link #스크립트를_실제로_실행했다()} 는 <b>자기 호출만</b> 지킨다.
     */
    private static void setUp(Path workingDir, String... command) throws Exception {
        ScanResult result = run(workingDir, command);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("테스트 준비가 실패했습니다: " + String.join(" ", command)
                    + " (exit=" + result.exitCode() + ")\n" + result.output());
        }
    }

    /**
     * ⚠️ {@code safety-boundary-check.sh} 는 {@code src/**} 의 {@code ProcessBuilder} 를 막는다.
     * S-3 이 금지하는 것은 <b>대상 저장소 코드</b>를 샌드박스 밖에서 돌리는 것이고,
     * 여기서 돌리는 것은 <b>우리 저장소의 하네스 스크립트</b>라 그 대상이 아니다 —
     * {@code testing-philosophy.md} 가 그렇게 정해 뒀다.
     *
     * <h2>출력을 파일로 받는 이유</h2>
     * 스트림을 먼저 다 읽고 나서 {@code waitFor(timeout)} 을 부르면 <b>타임아웃이 무효</b>다 —
     * stdout 을 닫지 않고 멈춘 프로세스에서 {@code readAllBytes} 가 영원히 블록되어
     * {@code waitFor} 에 도달하지 못한다. 순서를 뒤집으면 이번엔 파이프 버퍼가 차서 교착이다.
     * 파일로 빼면 <b>둘 다 생기지 않는다.</b>
     */
    private static ScanResult run(Path workingDir, String... command) throws Exception {
        Path log = Files.createTempFile("secret-scan-out", ".log");
        try {
            // 🕳 사유는 한 줄이어야 한다 — 훅은 위반 라인의 「바로 윗줄」만 본다. 이어짐 줄은 못 본다
            // safety-ok: 임시 디렉토리에서 git 플러밍과 우리 저장소의 .claude/scripts 만 돌린다. 대상 저장소 코드가 아니라 S-3 대상이 아니다
            ProcessBuilder builder = new ProcessBuilder(List.of(command))
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            isolateGitConfig(builder.environment(), workingDir);

            Process process = builder.start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("스크립트가 끝나지 않았습니다: " + String.join(" ", command));
            }
            return new ScanResult(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /**
     * 임시 저장소가 <b>바깥 설정을 하나도 보지 않게</b> 한다.
     *
     * <p>🔴 {@code HOME} 만 옮기는 것으로는 부족하다. {@code XDG_CONFIG_HOME} 이 설정돼 있으면
     * git 은 그쪽의 {@code git/config} 를 <b>계속 읽는다</b> — 실측으로 확인했다. 그 파일에
     * {@code core.hooksPath} 가 들어 있으면 <b>이 테스트가 개발자의 훅을 실행</b>하게 된다.
     * {@code GIT_CONFIG_GLOBAL} 로 전역 설정 자체를 {@code /dev/null} 에 고정해 닫는다.
     */
    private static void isolateGitConfig(Map<String, String> env, Path workingDir) {
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("HOME", workingDir.toString());
        env.remove("XDG_CONFIG_HOME");
    }

    private static void writeWithBom(Path file, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, withBom, 0, BOM.length);
        System.arraycopy(body, 0, withBom, BOM.length, body.length);
        Files.write(file, withBom);
    }
}
