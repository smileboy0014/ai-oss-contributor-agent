package com.ossagent.support.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 커밋 차단({@code secret-scan.sh})과 런타임 스크럽({@link TokenRedactor})이
 * <b>어긋나지 않는지</b> 검사한다 — S-4 · 이슈 #28.
 *
 * <p>두 곳이 어긋나면 한쪽만 막힌다. 실제로 그런 일이 이미 일어나 있었다 —
 * 스크립트에는 PEM 개인키 패턴이 있는데 런타임에는 없어서, <b>커밋은 막히지만
 * 프롬프트로는 나가는</b> 상태였다. 경고를 문서에 적어 두는 것과 어긋남을
 * <b>검출</b>하는 것은 다른 일이고, 이 테스트가 후자다.
 *
 * <h2>왜 패턴 파일을 공유하지 않았나</h2>
 * 셸과 Java 가 리터럴을 공유할 방법은 없다. 공통 파일을 <b>런타임이 읽게</b> 하면
 * 그 파일이 없는 환경에서 <b>스크럽이 조용히 0 이 된다</b> — 어긋나는 것보다 나쁘다.
 * 스크립트는 {@code .claude/}(개발 하네스)에 있고 배포물이 아니다.
 * 「공유」의 목적은 같은 파일을 쓰는 것이 아니라 <b>어긋나지 않는 것</b>이다.
 *
 * <h2>비교는 리터럴이 아니라 샘플 재현이다</h2>
 * 문자열 비교는 성립하지 않는다. 양쪽 정규식이 같을 이유가 없고 같아서도 안 된다 —
 * 스크립트의 PEM 패턴은 「이 파일을 커밋하지 마라」를 판정하는 <b>헤더 한 줄</b>이고,
 * 런타임은 내보낼 문자열에서 <b>키 본문을 지워야</b> 하므로 블록 전체다.
 * 그래서 {@link #ROWS} 의 각 행이 시크릿 모양의 샘플을 들고, 그것이
 * <b>(ⓐ 스크립트 정규식에 실제로 물리고 ⓑ 런타임에서 실제로 사라지는지</b>를 실행으로 본다.
 *
 * <h2>🕳 한계 — 추출기를 우회하는 법이 있다</h2>
 *
 * <p>아래는 전부 <b>조용히 통과</b>한다. 빨개지지 않으므로 아무도 모른다.
 *
 * <p><b>스크립트 쪽</b> — {@code scan_pattern} 인자형과 {@code grep -nE '…'} 인라인형만 문다.
 * {@code grep -nE "$pat"}(<b>큰따옴표</b>) · {@code grep -nEf} · {@code grep -P} ·
 * {@code egrep} 으로 패턴을 추가하면 추출되지 않고, 그러면 <b>커밋은 막는데 런타임에는
 * 없는</b> 상태가 검출되지 않는다.
 *
 * <p><b>런타임 쪽</b> — {@code Pattern.compile("…")} 의 <b>리터럴</b>만 센다.
 * {@code Pattern.compile(CONSTANT)} 이나 {@code compile("a" + "b")} 로 패턴을 늘리면
 * 개수 단언이 물지 않는다.
 *
 * <p>그리고 런타임 검사는 <b>개수를 셀 뿐</b> 그 패턴이 무엇을 가리는지 판정하지 못한다.
 * 숫자를 맞추는 것으로 때울 수 있다 — 그래서 이 표는 <b>강제 장치가 아니라 리뷰를
 * 부르는 장치</b>다.
 *
 * <p>✅ 반대로 <b>변수에 담아 {@code scan_pattern "$pat"} 로 넘기는 것</b>은 막힌다 —
 * 작은따옴표 인자가 하나가 아니게 되어 추출기가 빨갛게 터진다.
 */
class SecretPatternDriftTest {

    private static final Path SCRIPT = Path.of(".claude/scripts/secret-scan.sh");
    private static final Path REDACTOR_SOURCE =
            Path.of("src/main/java/com/ossagent/support/secret/TokenRedactor.java");

    /**
     * 🔴 {@code grep -nE '정규식'} 인라인 형태. <b>스크립트에서 PEM 하나만 이 모양이다.</b>
     *
     * <p>{@code scan_pattern} 만 훑는 추출기는 <b>하필 PEM 만 놓친다</b> — 이 테스트가
     * 닫으려는 바로 그 패턴이 검출기 사각지대가 된다. 그래서 두 형태를 모두 먹는다.
     *
     * <p>{@code -nE} 뒤에 <b>바로 붙은</b> 작은따옴표만 본다. {@code scan_pattern} 함수
     * 본문의 {@code grep -v '<REPLACE_WITH_SECRET_MANAGER>'} 를 패턴으로 오인하지 않기 위해서다.
     */
    private static final Pattern GREP_INLINE = Pattern.compile("grep\\s+-nE\\s+'([^']*)'");

    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^']*)'");

    private static final Pattern JAVA_PATTERN_COMPILE =
            Pattern.compile("Pattern\\.compile\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    // ── 픽스처 ────────────────────────────────────────────────────────────────
    // ⚠ 아래 토큰 샘플은 런타임 조립이다. testing-philosophy.md 는 조립을 「검사 회피」로
    //   보고 기본값에서 금지하되, 「토큰의 길이·문자셋이 실제로 유의미한 테스트」를 예외로
    //   둔다. 이 표가 정확히 그것이다 — 샘플이 정규식에 **물려야** 마스킹을 증명할 수 있고,
    //   권장 상수("ghp_NOT_A_REAL_TOKEN_FOR_TESTS_ONLY")는 밑줄 때문에 물리지 않는다.

    /** 넓힌 {@code gh[pousr]_} 를 대표하도록 {@code ghp_} 가 아닌 접두어를 쓴다. */
    private static final String SAMPLE_GITHUB_TOKEN = "ghs_" + "a".repeat(30);
    private static final String SAMPLE_FINE_GRAINED = "github" + "_pat_" + "c".repeat(30);
    private static final String SAMPLE_ANTHROPIC_KEY = "sk-" + "ant-" + "d".repeat(30);
    private static final String SAMPLE_AWS_KEY = "AKIA" + "EFGHIJKLMNOPQRST";
    private static final String SAMPLE_SLACK_TOKEN = "xox" + "b-" + "1".repeat(20);

    /**
     * 개인키 <b>본문</b>은 조립하지 않는다 — 가짜임이 한눈에 보이는 고정 문자열이다.
     *
     * <p>실제 PEM 본문처럼 base64 문자만 쓴다 — 스캐너가 「키 본문으로 볼 수 있는 줄」을
     * base64 모양으로 판정하기 때문이다.
     */
    private static final String FAKE_KEY_BODY = "NOTAREALKEYFORTESTSONLY";

    /**
     * 🔴 <b>PGP 형식을 대표 샘플로 둔다.</b> {@code PRIVATE KEY} 뒤에 {@code  BLOCK} 이
     * 끼는 형식이고, 이 PR 이전에는 <b>양쪽 모두</b> 이것을 놓치고 있었다.
     * 가장 잘 빠져나가는 모양을 대표로 세워야 대응표가 알리바이가 되지 않는다.
     */
    private static final String SAMPLE_PEM = String.join("\n",
            "-----BEGIN PGP PRIVATE KEY BLOCK-----",
            FAKE_KEY_BODY,
            "-----END PGP PRIVATE KEY BLOCK-----");

    /**
     * 스크립트 정규식 ↔ 런타임 커버리지 대응표.
     *
     * @param scriptRegex    스크립트에 적힌 그대로. 추출 결과와 <b>집합으로</b> 대조한다
     * @param sample         그 정규식에 물리는 시크릿 모양 문자열
     * @param mustDisappear  스크럽 후 남아 있으면 안 되는 조각
     */
    private record Row(String scriptRegex, String sample, String mustDisappear) {
    }

    private static final List<Row> ROWS = List.of(
            new Row("AKIA[0-9A-Z]{16}", SAMPLE_AWS_KEY, SAMPLE_AWS_KEY),
            new Row("gh[pousr]_[A-Za-z0-9]{20,}", SAMPLE_GITHUB_TOKEN, SAMPLE_GITHUB_TOKEN),
            new Row("github_pat_[A-Za-z0-9_]{20,}", SAMPLE_FINE_GRAINED, SAMPLE_FINE_GRAINED),
            new Row("xox[baprs]-[A-Za-z0-9-]{10,}", SAMPLE_SLACK_TOKEN, SAMPLE_SLACK_TOKEN),
            new Row("sk-ant-[A-Za-z0-9_-]{20,}", SAMPLE_ANTHROPIC_KEY, SAMPLE_ANTHROPIC_KEY),
            new Row("^-+ ?BEGIN [A-Z0-9 ]*PRIVATE KEY( BLOCK)?", SAMPLE_PEM, FAKE_KEY_BODY));

    /**
     * 스크립트에 대응이 <b>없어도 되는</b> 런타임 패턴. 사유 없이 늘리지 않는다.
     *
     * <p>커밋 차단으로 쓸 수 없는 것만 여기 온다. 「우리 저장소에 이 형태가 정상적으로
     * 등장하는가」가 기준이다.
     */
    private record RuntimeOnly(String reason, String sample, String mustDisappear) {
    }

    private static final List<RuntimeOnly> RUNTIME_ONLY = List.of(
            new RuntimeOnly(
                    "Authorization 헤더 값 — 'Authorization: Bearer …' 는 문서·테스트에 정상적으로"
                            + " 등장한다. 커밋 차단에 넣으면 오탐이 상시로 난다",
                    "Authorization: Bearer 사내프록시토큰값1234567890",
                    "사내프록시토큰값1234567890"),
            new RuntimeOnly(
                    "URL 에 박힌 자격증명 — 'https://user:pass@host' 형태는 문서·예시에"
                            + " 정상적으로 등장한다. 커밋 차단에 넣으면 오탐이 잦다",
                    "clone 실패: https://ci-bot:s3cr3tPassw0rd@git.example.com/x.git",
                    "s3cr3tPassw0rd"));

    /**
     * 스크립트의 PEM 한 행에 대응하는 <b>런타임 구현 패턴 수 - 1</b>.
     *
     * <p>런타임은 PEM 을 정규식 하나로 처리하지 않는다 — 헤더·푸터·본문 줄·머리말 줄
     * 네 패턴을 줄 단위 스캐너가 쓴다(2차식 폭발과 본문 파괴를 피하려고 그렇게 했다,
     * {@code TokenRedactor.redactPemBlocks}). 그중 헤더가 {@link #ROWS} 의 PEM 행에
     * 대응하고 나머지 셋이 여기 잡힌다.
     */
    private static final int PEM_IMPLEMENTATION_PATTERNS = 3;

    // ── 검사 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스크립트에서 추출한 패턴 집합이 대응표와 정확히 일치한다")
    void 스크립트_패턴이_대응표와_일치한다_S4() {
        Set<String> extracted = extractScriptPatterns();

        assertThat(extracted)
                .as("""
                        secret-scan.sh 의 패턴 목록이 이 테스트의 대응표와 어긋났다 — S-4.
                        패턴을 추가했다면 ROWS 에 행을 하나 더하고, 그 샘플이 TokenRedactor 에서
                        실제로 사라지는지 확인한다. 추가한 패턴이 런타임에 없으면
                        「커밋은 막히는데 프롬프트로는 나가는」 상태가 된다.
                        ⚠ 개수가 아니라 집합으로 본다 — 개수만 세면 하나가 빠지고 하나가 늘어도 통과한다.""")
                .containsExactlyInAnyOrderElementsOf(
                        ROWS.stream().map(Row::scriptRegex).toList());
    }

    @Test
    @DisplayName("추출기가 인라인 grep 형태를 먹는다 — PEM 이 사각지대가 아니다")
    void 추출기가_인라인_grep_형태를_먹는다_S4() {
        Set<String> extracted = extractScriptPatterns();

        assertThat(extracted)
                .as("""
                        스크립트의 패턴 표기가 두 가지다 — scan_pattern 인자형과 grep -nE 인라인형.
                        인라인형은 PEM 단 하나뿐이라, scan_pattern 만 훑는 추출기는
                        하필 이 테스트가 닫으려는 패턴만 놓친다.""")
                .anyMatch(regex -> regex.contains("PRIVATE KEY"));
    }

    @Test
    @DisplayName("대응표의 샘플이 스크립트 정규식에 실제로 물린다")
    void 대응표_샘플이_스크립트_정규식에_물린다_S4() {
        List<String> vacuous = new ArrayList<>();

        for (Row row : ROWS) {
            // PEM 은 줄 앵커(^)라 MULTILINE 이 없으면 입력 전체의 시작만 본다
            Pattern scriptPattern = Pattern.compile(row.scriptRegex(), Pattern.MULTILINE);
            if (!scriptPattern.matcher(row.sample()).find()) {
                vacuous.add(row.scriptRegex());
            }
        }

        assertThat(vacuous)
                .as("""
                        샘플이 스크립트 정규식에 물리지 않으면 그 행은 아무것도 증명하지 않는다.
                        「스크럽되었다」가 「그 패턴이 커버된다」를 뜻하려면 샘플이 먼저 대표여야 한다.""")
                .isEmpty();
    }

    @Test
    @DisplayName("대응표의 모든 샘플이 런타임에서 사라진다")
    void 모든_샘플이_런타임에서_사라진다_S4() {
        List<String> leaked = new ArrayList<>();

        for (Row row : ROWS) {
            String redacted = TokenRedactor.redact("앞 " + row.sample() + " 뒤");
            if (redacted.contains(row.mustDisappear())) {
                leaked.add(row.scriptRegex());
            }
        }

        assertThat(leaked)
                .as("""
                        커밋은 막는데 런타임은 흘려보내는 패턴이 있다 — S-4.
                        대상 저장소가 시크릿을 커밋해 뒀고 우리가 그 파일을 프롬프트에 실으면
                        그대로 모델 제공자에게 전송된다. 회수는 폐기·재발급뿐이다.""")
                .isEmpty();
    }

    @Test
    @DisplayName("스크립트에 대응이 없는 런타임 패턴은 등록된 것뿐이다")
    void 런타임_전용_패턴이_등록된_것뿐이다_S4() {
        int runtimePatterns = extractRuntimePatternCount();

        assertThat(runtimePatterns)
                .as("""
                        TokenRedactor 의 Pattern 개수가 등록된 수와 다르다 — S-4.
                        패턴을 더했다면 둘 중 하나를 하라.
                          · secret-scan.sh 에도 넣고 ROWS 에 행을 추가한다 (기본)
                          · 커밋 차단에 쓸 수 없는 사유가 있다면 RUNTIME_ONLY 에 사유와 함께 등록한다
                        ⚠ 이 검사는 개수만 본다. 무엇을 가리는 패턴인지는 판정하지 못하므로,
                          숫자를 맞추는 것으로 때우지 않는다 — 등록표를 실제로 갱신한다.""")
                .isEqualTo(ROWS.size() + RUNTIME_ONLY.size() + PEM_IMPLEMENTATION_PATTERNS);
    }

    @Test
    @DisplayName("런타임 전용 패턴이 실제로 동작한다")
    void 런타임_전용_패턴이_동작한다_S4() {
        List<String> broken = new ArrayList<>();

        for (RuntimeOnly only : RUNTIME_ONLY) {
            if (TokenRedactor.redact(only.sample()).contains(only.mustDisappear())) {
                broken.add(only.reason());
            }
        }

        assertThat(broken)
                .as("등록만 해 두고 실제로는 가리지 않으면 거짓 안전감만 남는다")
                .isEmpty();
    }

    @Test
    @DisplayName("검사 대상 파일을 실제로 읽었다")
    void 검사_대상_파일을_실제로_읽었다() {
        // 파일을 못 찾으면 위 검사들이 0건을 검사하고 조용히 통과할 수 있다.
        // 검증하지 않은 것을 통과라고 하지 않는다.
        assertThatCode(SecretPatternDriftTest::readScript)
                .as("작업 디렉토리가 프로젝트 루트가 아니면 %s 를 찾지 못한다", SCRIPT)
                .doesNotThrowAnyException();

        assertThat(extractScriptPatterns())
                .as("스크립트에서 패턴을 하나도 추출하지 못했다 — 표기 형태가 바뀌었을 수 있다")
                .isNotEmpty();
        assertThat(extractRuntimePatternCount())
                .as("TokenRedactor 소스에서 Pattern.compile 을 하나도 찾지 못했다")
                .isPositive();
    }

    // ── 추출기 ────────────────────────────────────────────────────────────────

    /**
     * 스크립트의 <b>두 표기 형태</b>에서 정규식을 모은다.
     *
     * <p>먼저 줄 이어짐({@code \} + 개행)을 접는다. {@code scan_pattern} 호출이 세 줄에
     * 걸쳐 있어, 접지 않으면 정규식 인자가 호출과 다른 줄에 남는다.
     */
    private static Set<String> extractScriptPatterns() {
        String folded = readScript().replaceAll("\\\\\\R\\s*", " ");
        Set<String> patterns = new LinkedHashSet<>();

        for (String line : folded.split("\\R")) {
            Matcher inline = GREP_INLINE.matcher(line);
            while (inline.find()) {
                patterns.add(inline.group(1));
            }

            String trimmed = line.trim();
            if (!trimmed.startsWith("scan_pattern ")) {
                continue;
            }
            List<String> quoted = new ArrayList<>();
            Matcher arg = SINGLE_QUOTED.matcher(trimmed);
            while (arg.find()) {
                quoted.add(arg.group(1));
            }
            // 호출당 작은따옴표 인자는 정규식 하나뿐이다. 늘면 추출이 모호해지므로
            // 조용히 하나를 고르지 않고 빨갛게 터뜨린다 — 취약함의 방향을 고른 것이다.
            assertThat(quoted)
                    .as("scan_pattern 호출의 작은따옴표 인자가 하나가 아니다: %s", trimmed)
                    .hasSize(1);
            patterns.add(quoted.get(0));
        }
        return patterns;
    }

    private static int extractRuntimePatternCount() {
        Matcher matcher = JAVA_PATTERN_COMPILE.matcher(read(REDACTOR_SOURCE));
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String readScript() {
        return read(SCRIPT);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("검사 대상 파일을 읽지 못했습니다: " + path.toAbsolutePath(), e);
        }
    }
}
