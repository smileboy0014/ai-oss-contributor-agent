package com.ossagent.support.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /**
     * latin-1 {@code é}. 단독으로는 <b>유효한 UTF-8 이 아니다</b> — UTF-8 로케일의
     * BSD·GNU grep 은 이 바이트를 만나면 <b>그 뒤쪽을 매칭에서 버린다</b>.
     *
     * <p>파일이 스캔 대상에서 빠지는 것이 아니라 <b>스캔했는데 히트 0건</b>이라,
     * 로그로는 통과와 구분되지 않는다(#75).
     *
     * <p>🔴 <b>위치가 가른다.</b> 실측 — 매치 대상 <b>앞</b>에 두면 0건(샌다),
     * <b>뒤</b>에 두면 1건이다. 그래서 표본은 전부 <b>바이트를 앞에</b> 둔다.
     * 뒤에 두면 수정을 제거해도 초록이라 아무것도 재지 못한다.
     */
    private static final byte INVALID_UTF8 = (byte) 0xE9;

    /**
     * ⚠️ 런타임 조립이다. {@code testing-philosophy.md} 는 조립을 「검사 회피」로 보고
     * 기본값에서 금지하되 <b>「토큰의 길이·문자셋이 실제로 유의미한 테스트」</b>를 예외로 둔다.
     * 이 회귀가 정확히 그것이다 — 샘플이 스크립트 정규식에 <b>물려야</b> 「가려지지 않았다」를
     * 증명할 수 있고, 권장 상수({@code ghp_NOT_A_REAL_TOKEN_FOR_TESTS_ONLY})는 밑줄 때문에
     * {@code [A-Za-z0-9]{20,}} 에 물리지 않는다. {@link SecretPatternDriftTest} 와 같은 판단이다.
     */
    private static final String SAMPLE_TOKEN = "ghp_" + "NOTAREALTOKENFORTESTSONLY";

    /**
     * 상속 로케일을 UTF-8 로 고정한다 — 스크립트의 {@code export LC_ALL=C} 가
     * <b>물려받은 값을 이기는지</b> 보기 위해서다.
     *
     * <p>⚠️ 이 로케일이 없는 환경에서는 {@code setlocale} 이 {@code C} 로 떨어져
     * <b>구멍이 재현되지 않는다.</b> 그래도 단언은 「차단된다」 하나라 <b>거짓 실패가 나지
     * 않는다</b> — 재현되면 수정이 막는 것을, 재현되지 않으면 원래 검사가 잡는 것을 본다.
     */
    private static final Map<String, String> UTF8_LOCALE =
            Map.of("LC_ALL", "C.UTF-8", "LANG", "C.UTF-8");

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
    @DisplayName("🔴 양성 대조 — 이 환경에서 구멍이 실제로 재현된다")
    void 물림의_전제가_이_환경에서_성립한다_S4(@TempDir Path repo) throws Exception {
        // 🔴 「초록의 원인이 둘이다」를 가른다 — 아래 회귀들이 초록인 이유가
        //    **수정이 막아서**인지 **애초에 구멍이 없어서**인지 구분되지 않으면 공허하다.
        //
        //    이 검사는 게이트를 **끈 사본**(로케일 고정 제거 + 자가 점검 무력화)을
        //    UTF-8 로케일로 돌린다. 여기서 「통과」가 나와야 구멍이 살아 있는 것이고,
        //    그래야 아래 회귀의 「차단」이 수정의 공로가 된다.
        //
        //    ⚠ 「빨개지면 나쁨」이 아니다 — 이 환경의 grep·로케일 조합에 구멍이 없다는
        //    뜻이고(ugrep · musl 의 C 폴백 등), 그때는 **명시적으로 skip** 한다.
        //    조용히 초록이 되는 것만 막으면 된다.
        writeWithInvalidByte(repo.resolve("note.txt"), "caf", " " + SAMPLE_TOKEN + "\n");

        ScanResult unguarded = scan(repo, "note.txt", SecretScanScriptTest::disableLocaleGuard,
                UTF8_LOCALE);

        assumeTrue(!unguarded.blocked(), """
                이 실행 환경에서는 #75 의 구멍이 재현되지 않는다 — 아래 회귀들은 물림을
                증명하지 못하고 「원래 잡히던 것을 계속 잡는다」까지만 본다.
                grep 구현이 부정 바이트 입력을 통째로 건너뛰거나(ugrep),
                C.UTF-8 이 없어 C 로 폴백했을 수 있다. 출력:
                %s""".formatted(unguarded.output()));

        assertThat(unguarded.output())
                .as("구멍이 재현됐다면 게이트는 **스캔하고 0건**을 냈어야 한다 — 파일이 빠진 것이 아니다")
                .contains("시크릿 검사 통과");
    }

    @Test
    @DisplayName("토큰과 같은 줄에 비-UTF-8 바이트가 있어도 차단된다")
    void 같은_줄의_비UTF8_바이트가_토큰을_가리지_못한다_S4(@TempDir Path repo) throws Exception {
        writeWithInvalidByte(repo.resolve("note.txt"), "caf", " " + SAMPLE_TOKEN + "\n");

        // ⚠ 상속 로케일을 고정한다. 안 하면 JVM 이 물려받은 값에 좌우돼,
        //   LANG 을 주지 않는 CI 에서는 수정 없이도 초록이 된다(#75 리뷰).
        ScanResult result = scan(repo, "note.txt", Function.identity(), UTF8_LOCALE);

        assertThat(result.blocked())
                .as("""
                        토큰과 **같은 줄**에 부정한 바이트가 있으면 UTF-8 로케일의 grep 이 그 줄을
                        통째로 건너뛴다. 파일이 스캔 대상에서 빠진 것이 아니라 **스캔했는데 0건**이라
                        로그로는 통과와 구분되지 않는다 — 가장 나쁜 실패 모양이다.
                        출력:
                        %s""", result.output())
                .isTrue();
        assertThat(result.output()).contains("GitHub Token");
    }

    @Test
    @DisplayName("상속 로케일이 UTF-8 이어도 토큰이 차단된다")
    void 상속_로케일이_UTF8_이어도_토큰이_차단된다_S4(@TempDir Path repo) throws Exception {
        // 🔴 이것이 수정의 본체다 — 스크립트가 **물려받은 로케일을 이겨야** 한다.
        //    사람마다 LANG 이 다른 것이 이 계열 버그의 본질적 위험이고(#61), 게이트는
        //    환경에 관계없이 같은 판정을 내야 한다.
        writeWithInvalidByte(repo.resolve("note.txt"), "caf", " " + SAMPLE_TOKEN + "\n");

        ScanResult result = scan(repo, "note.txt", Function.identity(), UTF8_LOCALE);

        assertThat(result.blocked())
                .as("""
                        UTF-8 로케일을 물려받아도 게이트 판정이 바뀌면 안 된다.
                        출력:
                        %s""", result.output())
                .isTrue();
    }

    @Test
    @DisplayName("서비스계정 키 JSON 의 앞쪽 필드에 비-UTF-8 바이트가 있어도 차단된다")
    void 같은_줄의_비UTF8_바이트가_서비스계정_키를_가리지_못한다_S4(@TempDir Path repo) throws Exception {
        // 🔴 토큰 검사와 **같이 증명되지 않는** 경로다 — 키 「파일」 포맷이고, 한 줄에
        //    담긴 JSON 은 부정 바이트가 앞 필드에 있어도 키 전체가 뒤에 온다.
        //    client_email 에 latin-1 바이트 하나면 파일 전체가 샌다(실측).
        //
        //    ⚠ 앵커(^)를 쓰는 PEM 헤더 검사는 이 구멍에 **원리적으로 닿지 않는다.**
        //    grep 이 버리는 것은 「줄 전체」가 아니라 **부정 바이트 뒤쪽**이고,
        //    헤더는 줄 시작에 와야 하므로 그 앞의 바이트는 앵커를 정당하게 깨뜨린다.
        //    그 경로로 테스트를 쓰면 돌연변이를 넣어도 **빨개지지 않는다** — 실제로 확인했다.
        writeWithInvalidByte(repo.resolve("sa.json"),
                "{\"client_email\":\"caf",
                "@x.iam\",\"private_key\":\"-----BEGIN RSA PRIVATE KEY-----\"}\n");

        ScanResult result = scan(repo, "sa.json", Function.identity(), UTF8_LOCALE);

        assertThat(result.blocked())
                .as("""
                        서비스계정 키 JSON 은 **공개 저장소에 가장 흔히 커밋되는 키 파일 포맷**이다.
                        앞 필드의 바이트 하나로 그 줄의 뒤쪽이 매칭에서 사라지면 파일 전체가 샌다.
                        출력:
                        %s""", result.output())
                .isTrue();
        assertThat(result.output()).contains("Private Key (JSON)");
    }

    @Test
    @DisplayName("비-UTF-8 바이트가 든 정상 파일은 통과한다")
    void 비UTF8_바이트만으로는_차단하지_않는다(@TempDir Path repo) throws Exception {
        // 부정한 바이트 자체를 위험 신호로 다루지 않는다 — 과차단하면 사람이 게이트를
        // 끄고 싶어진다. latin-1 로 저장된 옛 파일은 저장소에 정상적으로 존재할 수 있다.
        writeWithInvalidByte(repo.resolve("legacy.txt"), "caf",
                " 는 latin-1 로 저장된 옛 메모다.\n키는 Secret Manager 에 둔다.\n");

        assertThat(scan(repo, "legacy.txt", Function.identity(), UTF8_LOCALE).blocked())
                .as("넓힌 판정이 과차단으로 뒤집히면 수정이 아니라 다른 고장이다")
                .isFalse();
    }

    @Test
    @DisplayName("로케일 자가 점검이 고장나면 조용히 통과하지 않고 멈춘다")
    void 로케일_자가점검이_고장나면_시끄럽게_멈춘다_S4(@TempDir Path repo) throws Exception {
        // 🔴 export 로도 닫히지 않는 경로가 둘 있다 — 누가 export 를 지우거나, LC_ALL 을
        //    무시하는 grep 구현으로 도는 것. 둘 다 증상이 「히트 0건 → ✅ 통과」다.
        //    기동 자가 점검이 그것을 잡는데, **그 점검 자체가 조용히 죽으면** 원점이다.
        //
        //    ⚠ 치환이 빗나가면(스크립트 문구가 바뀌면) 스크립트가 그대로 돌아 통과하고
        //      아래 단언이 빨개진다 — 드리프트가 조용히 넘어가지 않는다.
        Files.writeString(repo.resolve("readme.md"), "본문\n");

        ScanResult result = scan(repo, "readme.md",
                script -> script.replace("| grep -qE 'SENTINEL", "| no_such_cmd -qE 'SENTINEL"));

        assertThat(result.blocked())
                .as("""
                        점검이 고장났는데 exit 0 이면 게이트는 눈을 감은 채 초록을 찍는다.
                        출력:
                        %s""", result.output())
                .isTrue();
        assertThat(result.output())
                .as("무엇이 고장났는지 말해야 사람이 고칠 수 있다")
                .contains("부정한 바이트가 섞인 줄");
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
        assertThat(result.output())
                .as("""
                        **상속** 로케일도 남아야 한다 — 판정이 머신마다 갈린 원인이 그것이다(#75).
                        강제한 값(항상 C)만 찍으면 두 환경의 초록을 대조할 수 없다.""")
                .contains("상속:");
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

    /**
     * 로케일 방어를 <b>둘 다</b> 끈다 — 양성 대조 전용(#75).
     *
     * <p>🔴 <b>둘 다 꺼야 한다.</b> {@code export} 만 지우면 기동 자가 점검이 먼저 발화해
     * 게이트가 통째로 멈추고, 그러면 재고 싶었던 <b>유출</b>이 아니라 <b>자가 점검의 발화</b>를
     * 재게 된다 — {@code testing-philosophy.md} 의 「제거 지점이 측정 대상보다 위」다.
     *
     * <p>센티널을 유효한 UTF-8({@code cafe})로 바꿔 점검이 항상 통과하게 만든다.
     * 구조와 {@code | grep -qE 'SENTINEL} 리터럴은 남으므로 다른 돌연변이 검사와 겹치지 않는다.
     */
    private static String disableLocaleGuard(String script) {
        return script
                .replace("\nexport LC_ALL=C\n", "\n")
                .replace("'caf\\xe9 SENTINEL", "'cafe SENTINEL");
    }

    private static ScanResult scan(Path repo, String target, Function<String, String> mutate)
            throws Exception {
        return scan(repo, target, mutate, Map.of());
    }

    /**
     * @param extraEnv 스크립트 프로세스에만 덧씌우는 환경변수. 준비용 git 명령에는 적용하지
     *     않는다 — 재는 것은 <b>스크립트가 물려받은 로케일</b>이지 git 의 동작이 아니다.
     */
    private static ScanResult scan(Path repo, String target, Function<String, String> mutate,
            Map<String, String> extraEnv) throws Exception {
        setUp(repo, "git", "init", "-q");
        setUp(repo, "git", "config", "user.email", "test@example.com");
        setUp(repo, "git", "config", "user.name", "test");

        Path scripts = repo.resolve(".claude/scripts");
        Files.createDirectories(scripts);
        Path copied = scripts.resolve("secret-scan.sh");
        Files.writeString(copied, mutate.apply(Files.readString(SCRIPT, StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        setUp(repo, "git", "add", target);
        return run(repo, extraEnv, "bash", copied.toString());
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
        ScanResult result = run(workingDir, Map.of(), command);
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
    private static ScanResult run(Path workingDir, Map<String, String> extraEnv, String... command)
            throws Exception {
        Path log = Files.createTempFile("secret-scan-out", ".log");
        try {
            // 🕳 사유는 한 줄이어야 한다 — 훅은 위반 라인의 「바로 윗줄」만 본다. 이어짐 줄은 못 본다
            // safety-ok: 임시 디렉토리에서 git 플러밍과 우리 저장소의 .claude/scripts 만 돌린다. 대상 저장소 코드가 아니라 S-3 대상이 아니다
            ProcessBuilder builder = new ProcessBuilder(List.of(command))
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            isolateGitConfig(builder.environment(), workingDir);
            builder.environment().putAll(extraEnv);

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

    /**
     * {@code before} + <b>유효하지 않은 UTF-8 바이트 1개</b> + {@code after} 를 쓴다.
     *
     * <p>Java 문자열로는 만들 수 없다 — {@code 0xE9} 단독은 UTF-8 로 인코딩되지 않는다.
     * 바이트로 이어 붙여야 grep 이 「부정한 바이트열」로 보는 그 입력이 된다.
     */
    private static void writeWithInvalidByte(Path file, String before, String after)
            throws IOException {
        byte[] head = before.getBytes(StandardCharsets.UTF_8);
        byte[] tail = after.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + 1 + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        all[head.length] = INVALID_UTF8;
        System.arraycopy(tail, 0, all, head.length + 1, tail.length);
        Files.write(file, all);
    }

    private static void writeWithBom(Path file, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, withBom, 0, BOM.length);
        System.arraycopy(body, 0, withBom, BOM.length, body.length);
        Files.write(file, withBom);
    }
}
