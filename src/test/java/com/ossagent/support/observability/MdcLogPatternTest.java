package com.ossagent.support.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.ossagent.support.testing.AgentIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 🔴 <b>MDC 가 로그에 실리는가, 그리고 거기 외부 텍스트가 없는가</b> — #25 FR-5 · S-4.
 *
 * <h2>왜 이 테스트가 필요한가</h2>
 *
 * <p>코드는 MDC 를 성실히 채우고 있었는데 <b>출력 포맷이 그것을 버리고 있었다.</b>
 * {@code logging.md} 가 「로그 포맷에 {@code %X{candidateId}} … 를 포함시킨다」고 요구한
 * 그대로가 미구현이었고, <b>아무도 몰랐다</b> — 로그가 그냥 조금 덜 유용했을 뿐이라
 * 실패로 보이지 않았기 때문이다.
 *
 * <p>같은 일이 다시 일어나지 않게 <b>패턴 렌더링 자체</b>를 고정한다.
 * {@code ListAppender} 로 이벤트만 보면 포맷을 검증하지 못한다 — 포맷이 비어 있어도
 * 이벤트에는 MDC 가 들어 있다.
 *
 * <p>⚠️ <b>{@code @AgentIntegrationTest} 여야 한다.</b> {@code logback-spring.xml} 은
 * Spring Boot 의 로깅 초기화가 적용하는 파일이라, 순수 JUnit 테스트에서는 <b>읽히지 않는다</b>
 * (logback 이 직접 찾는 것은 {@code logback.xml}·{@code logback-test.xml} 이다).
 * 컨텍스트 없이 짜면 「어펜더를 못 찾았다」로 빨개지거나, 더 나쁘게는 테스트가
 * 자기 패턴을 만들어 <b>아무것도 검증하지 않게</b> 된다.
 *
 * <h2>🔴 MDC 에 외부 텍스트를 넣지 않는다</h2>
 *
 * <p>로그 포맷은 <b>모든 줄</b>에 붙으므로 여기가 오염되면 전부 오염된다.
 * 소스 전체를 훑어 {@code MDC.put} 의 키가 허용 목록 안인지 본다.
 */
@AgentIntegrationTest
class MdcLogPatternTest {

    /**
     * 🔴 허용되는 MDC 키. 값은 전부 <b>식별자 또는 enum</b> 이어야 한다.
     *
     * <p>{@code repositoryId} 는 이슈 #25 가 지정한 3키에 없지만 실제 MDC 에 존재하므로
     * 포함한다 — 이슈 범위를 넘는 추가임을 밝혀 둔다.
     */
    private static final Set<String> ALLOWED_MDC_KEYS =
            Set.of("repositoryId", "candidateId", "stage", "attempt");

    /**
     * 🔴 <b>키를 리터럴·상수로 주지 않는 경로까지 잡는다.</b>
     *
     * <p>초안은 리터럴과 대문자 상수만 봐서 <b>소문자 변수로 키를 주면 매치 자체가 안 되어
     * 조용히 빠졌다.</b> {@code resolveConstant} 의 fail-closed 설계는 「해석 못 했으니
     * 통과」를 막지만, 그 방어는 <b>매치가 됐을 때만</b> 걸린다 — 「존재를 못 봤다」는
     * 막지 못한다.
     *
     * <p>{@code putCloseable}·{@code setContextMap} 도 같은 이유로 포함한다.
     */
    private static final Pattern MDC_WRITE = Pattern.compile(
            "MDC\\.(put|putCloseable|setContextMap)\\(\\s*([^,)\\s]+)");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("🔴 MDC 값이 실제 로그 출력에 실린다 — 포맷이 그것을 버리지 않는다")
    void MDC_가_로그_포맷에_실린다() {
        MDC.put("repositoryId", "7");
        MDC.put("candidateId", "42");
        MDC.put("stage", "ANALYZE");
        MDC.put("attempt", "1");

        String rendered = render();

        assertThat(rendered)
                .as("""
                        MDC 가 출력에 없다 — application.yml 의 logging.pattern.correlation 을 확인한다.
                        코드가 MDC 를 채워도 포맷이 버리면 「한 후보가 여러 단계를 거치므로
                        식별자로 로그를 이어붙인다」(logging.md)가 성립하지 않는다.""")
                .contains("7")
                .contains("42")
                .contains("ANALYZE");
    }

    @Test
    @DisplayName("MDC 가 비어도 리터럴이 찍히지 않는다 — %X{key:-} 의 기본값")
    void MDC_가_비면_빈_칸이다() {
        String rendered = render();

        assertThat(rendered)
                .as("기본값을 주지 않으면 logback 이 'candidateId_IS_UNDEFINED' 를 찍는다")
                .doesNotContain("IS_UNDEFINED");
    }

    @Test
    @DisplayName("🔴 코드가 MDC 에 넣는 키가 허용 목록 안이다 — 외부 텍스트 금지 S4")
    void MDC_에_외부_텍스트를_넣지_않는다_S4() throws IOException {
        List<String> unexpected;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            unexpected = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(MdcLogPatternTest::mdcKeysIn)
                    .distinct()
                    .filter(key -> !ALLOWED_MDC_KEYS.contains(key))
                    .toList();
        }

        assertThat(unexpected)
                .as("""
                        허용되지 않은 MDC 키가 있다 — S-4.
                        로그 포맷은 모든 줄에 붙으므로 여기가 오염되면 전부 오염된다.
                        값이 식별자·enum 인지 확인하고 ALLOWED_MDC_KEYS 에 등록한다.
                        이슈 제목·본문·LLM 응답·예외 메시지는 절대 넣지 않는다.""")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠ 양성 대조 — 스캐너가 실제로 MDC.put 을 찾는다")
    void 스캐너가_실제로_키를_찾는다() throws IOException {
        List<String> found;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            found = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(MdcLogPatternTest::mdcKeysIn)
                    .distinct()
                    .toList();
        }

        assertThat(found)
                .as("하나도 못 찾으면 위 테스트가 0건을 검사하고 초록이 된 것이다")
                .isNotEmpty()
                .contains("candidateId", "stage");
    }

    /**
     * ⚠️ 상수로 쓴 키({@code MDC.put(MDC_STAGE, …)})도 잡는다 — 리터럴만 보면
     * {@code ScanPipelineUseCase} 를 놓친다. 상수 이름은 따로 해석하지 않고
     * <b>같은 파일에서 그 상수의 값</b>을 찾는다.
     */
    private static Stream<String> mdcKeysIn(Path path) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("소스를 읽지 못했다: " + path, e);
        }
        List<String> keys = new java.util.ArrayList<>();
        Matcher matcher = MDC_WRITE.matcher(source);
        while (matcher.find()) {
            String method = matcher.group(1);
            String keyExpression = matcher.group(2).trim();

            if ("setContextMap".equals(method)) {
                // 🔴 맵을 통째로 넣으면 키를 정적으로 알 수 없다 — 그 자체를 실패로 본다
                keys.add("setContextMap(" + keyExpression + ")");
            } else if (keyExpression.startsWith("\"")) {
                keys.add(keyExpression.substring(1, keyExpression.lastIndexOf('"')));
            } else if (keyExpression.matches("[A-Z][A-Z0-9_]*")) {
                keys.add(resolveConstant(source, keyExpression));
            } else {
                // 🔴 소문자 변수·필드·메서드 호출 — 키를 정적으로 알 수 없다.
                //    「모르면 통과」가 아니라 「모르면 실패」다. 허용 목록에 없는 값을
                //    돌려주므로 테스트가 빨개지고, 그때 사람이 판단한다
                keys.add("<동적 키: " + keyExpression + ">");
            }
        }
        return keys.stream();
    }

    private static String resolveConstant(String source, String constantName) {
        Matcher value = Pattern.compile(constantName + "\\s*=\\s*\"([^\"]+)\"").matcher(source);
        // 못 찾으면 상수 이름을 그대로 돌려준다 — 허용 목록에 없으므로 테스트가 빨개진다.
        // 「해석 못 했으니 통과」가 되지 않게 한다
        return value.find() ? value.group(1) : constantName;
    }

    /** 실제 logback 설정으로 한 줄을 렌더링한다 — 포맷 자체를 본다. */
    private static String render() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("mdc-probe");
        ch.qos.logback.classic.spi.LoggingEvent event = new ch.qos.logback.classic.spi.LoggingEvent(
                MdcLogPatternTest.class.getName(), logger,
                ch.qos.logback.classic.Level.INFO, "탐침", null, null);
        event.setMDCPropertyMap(MDC.getCopyOfContextMap() == null
                ? java.util.Map.of() : MDC.getCopyOfContextMap());

        ch.qos.logback.classic.LoggerContext context =
                (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder =
                new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(consolePattern(context));
        encoder.start();
        try {
            return new String(encoder.encode(event), StandardCharsets.UTF_8);
        } finally {
            encoder.stop();
        }
    }

    /** 🔴 설정 파일의 실제 패턴을 읽는다 — 테스트가 자기 패턴을 만들면 아무것도 검증하지 못한다. */
    private static String consolePattern(ch.qos.logback.classic.LoggerContext context) {
        ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
                        .getAppender("CONSOLE");
        assertThat(appender)
                .as("Boot 의 CONSOLE 어펜더를 찾지 못했다 — 로깅 초기화가 안 됐다(@AgentIntegrationTest 인가?)")
                .isNotNull();
        var encoder = ((ch.qos.logback.core.OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>)
                appender).getEncoder();
        return ((ch.qos.logback.classic.encoder.PatternLayoutEncoder) encoder).getPattern();
    }
}
