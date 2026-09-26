package com.ossagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 프롬프트가 <b>조립·송신되는 경계</b>를 고정한다 — S-4 · 이슈 #28.
 *
 * <p>#10 이 어댑터에서 스크럽하는 것은 <b>송신 일원화</b>이지 조립 일원화가 아니다.
 * 조립을 우회하는 구멍은 「스크럽을 안 부르는 것」이 아니라 <b>어댑터를 통하지 않고
 * SDK 를 직접 쓰는 것</b>이고, 그러면 {@code PromptScrubber} 가 걸릴 자리 자체가 없다.
 *
 * <h2>왜 소스 텍스트를 읽나</h2>
 * 두 규칙 다 「이 코드가 존재하는가」를 묻는 것이지 「이 객체가 무엇인가」를 묻는 것이
 * 아니다. 리플렉션은 import 도, {@code log.*} 호출의 인자 모양도 보지 못한다.
 *
 * <h2>🕳 한계 — 취약함의 방향을 골랐다</h2>
 * 소스 텍스트 검사라 표현이 바뀌면 깨진다. 다만 깨지는 방향이 <b>「조용히 통과」가 아니라
 * 「무고하게 빨개짐」</b>이고, 대상을 {@code agent} 패키지로 좁혀 폭발 반경을 줄였다.
 * <b>오탐이 잦다고 이 테스트를 지우지 않는다 — 대상을 더 좁힌다.</b>
 */
class PromptBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java/com/ossagent");
    private static final Path AGENT = MAIN.resolve("agent");

    // ── FR-1: SDK 사용처 ──────────────────────────────────────────────────────

    /**
     * {@code com.anthropic} 을 직접 써도 되는 곳.
     *
     * <p>{@code config} 가 허용인 이유는 architecture.md 가 그 패키지를 <b>조립 전용</b>으로
     * 정의하기 때문이다. 비즈니스 코드가 들어가면 그쪽 규율이 먼저 막는다.
     */
    private static final Set<String> ALLOWED_SDK_USERS = Set.of(
            "agent/adapter/out/llm/AnthropicLanguageModel.java",
            "config/LanguageModelConfig.java");

    @Test
    @DisplayName("LLM SDK 는 어댑터와 조립 지점에서만 쓴다")
    void LLM_SDK_사용처가_제한된다_S4() {
        List<String> users = sourceFiles(MAIN)
                .filter(path -> read(path).contains("com.anthropic"))
                .map(PromptBoundaryTest::relativeToMain)
                .sorted()
                .toList();

        assertThat(users)
                .as("""
                        com.anthropic SDK 를 LLM 어댑터 밖에서 직접 쓰고 있다 — S-4.

                        그 자리에는 PromptScrubber 가 걸릴 이음매가 없다. #10 이 세운
                        「생성자가 scrubber == null 을 거부한다」는 보장이 통째로 비껴간다.

                        고치는 법: LanguageModel 능력 인터페이스를 주입받아 쓴다.
                        조립이 필요하면 config 에 둔다(architecture.md — 조립 전용).""")
                .containsExactlyInAnyOrderElementsOf(ALLOWED_SDK_USERS);
    }

    // ── FR-5: 로그에 본문이 실리지 않는다 ──────────────────────────────────────

    private static final Pattern LOG_CALL =
            Pattern.compile("\\blog\\.(?:trace|debug|info|warn|error)\\(([^;]*)\\)\\s*;",
                    Pattern.DOTALL);

    /** 외부 텍스트를 담기 쉬운 식별자. 이름으로 보는 것이라 타입 검사가 아니다. */
    private static final List<String> BODY_BEARING = List.of(
            "prompt", "systemPrompt", "userPrompt", "system", "text", "body",
            "content", "diff", "rawResponse", "response", "output");

    /**
     * 본문을 꺼내는 접근자. 이 뒤에 붙으면 스칼라가 아니라 <b>문자열</b>이 나온다.
     *
     * <p>반대로 {@code .length()} · {@code .usage()} 처럼 스칼라를 내는 접근자는 통과시킨다 —
     * {@code logging.md} 가 「크기와 해시만」이라고 한 그 형태가 정상이기 때문이다.
     */
    private static final List<String> BODY_ACCESSORS = List.of(
            "substring", "toString", "body", "content", "text", "prompt", "diff");

    @Test
    @DisplayName("프롬프트 경로의 로그에 본문이 실리지 않는다")
    void 프롬프트_경로_로그에_본문이_실리지_않는다_S4() {
        List<String> suspects = new ArrayList<>();

        for (Path path : sourceFiles(AGENT).toList()) {
            Matcher call = LOG_CALL.matcher(read(path));
            while (call.find()) {
                String arguments = call.group(1);
                for (String identifier : BODY_BEARING) {
                    if (leaksBody(arguments, identifier)) {
                        suspects.add(relativeToMain(path) + " — " + identifier);
                    }
                }
            }
        }

        assertThat(suspects)
                .as("""
                        LLM 프롬프트·응답 본문이 로그로 나갈 수 있다 — S-4.

                        logging.md: 프롬프트 전문은 debug + 스크럽 후에만. 대용량 payload 는
                        「크기와 해시만」. 대상 저장소가 시크릿을 커밋해 뒀을 수 있고,
                        외부 텍스트를 포맷 문자열로 쓰면 로그 인젝션도 된다.

                        고치는 법: 값 대신 길이·건수·식별자를 남긴다 (text.length() 처럼).

                        ⚠ 이 검사는 이름으로 본다 — 타입 검사가 아니다. 오탐이면 변수명을
                        바꾸거나 스칼라 접근자를 쓴다. 검사를 지우지 않는다.""")
                .isEmpty();
    }

    @Test
    @DisplayName("검사 대상 소스를 실제로 읽었다")
    void 검사_대상_소스를_실제로_읽었다() {
        // 경로가 틀리면 위 두 검사가 0건을 훑고 조용히 통과한다
        assertThat(sourceFiles(AGENT).toList())
                .as("agent 패키지에서 소스를 하나도 찾지 못했다 — 작업 디렉토리나 경로가 잘못됐다")
                .isNotEmpty();

        long logCalls = sourceFiles(AGENT)
                .mapToLong(path -> LOG_CALL.matcher(read(path)).results().count())
                .sum();
        assertThat(logCalls)
                .as("agent 패키지에서 log 호출을 하나도 찾지 못했다 — 정규식이 표현을 못 따라간다")
                .isPositive();
    }

    @Test
    @DisplayName("본문 유출 판정이 실제로 문다")
    void 본문_유출_판정이_실제로_문다() {
        // 위반이 0건이라 음성만 관찰된다. 판정기가 항상 false 를 돌려줘도 초록이므로 물림을 고정한다
        assertThat(leaksBody("\"프롬프트={}\", prompt", "prompt"))
                .as("맨몸으로 실린 본문을 잡지 못하면 이 검사는 무력하다")
                .isTrue();
        assertThat(leaksBody("\"요약={}\", response.content()", "response"))
                .as("본문 접근자를 거친 경우도 잡아야 한다")
                .isTrue();
        assertThat(leaksBody("\"길이={}\", text.length()", "text"))
                .as("스칼라 접근자는 정상이다. 이것을 잡으면 오탐으로 검사가 버려진다")
                .isFalse();
        assertThat(leaksBody("\"토큰={}\", response.usage().inputTokens()", "response"))
                .as("logging.md 가 남기라고 요구하는 바로 그 형태다 — 막으면 안 된다")
                .isFalse();
    }

    /** 이 식별자가 로그 인자에 <b>본문을 실어</b> 등장하는가. */
    private static boolean leaksBody(String arguments, String identifier) {
        Matcher use = Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b\\s*(\\.\\s*(\\w+))?")
                .matcher(arguments);
        while (use.find()) {
            String accessor = use.group(2);
            if (accessor == null || BODY_ACCESSORS.contains(accessor)) {
                return true;   // 맨몸이거나, 본문을 꺼내는 접근자
            }
        }
        return false;
    }

    private static Stream<Path> sourceFiles(Path root) {
        try {
            return Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException("소스를 훑지 못했습니다: " + root.toAbsolutePath(), e);
        }
    }

    private static String relativeToMain(Path path) {
        return MAIN.relativize(path).toString().replace('\\', '/');
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("소스를 읽지 못했습니다: " + path.toAbsolutePath(), e);
        }
    }
}
