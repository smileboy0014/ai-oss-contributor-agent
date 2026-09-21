package com.ossagent.support.secret;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 토큰·키 패턴을 마스킹한다 — {@code .claude/rules/context/safety-boundaries.md} S-4.
 *
 * <p>가장 흔한 사고는 예외 메시지다. HTTP 클라이언트 예외에 요청 URL·헤더가 담기고,
 * 거기에 토큰이 붙어 있으면 로그·에러 응답·{@code AgentRun.errorMessage} 로 그대로 나간다.
 * 회수는 폐기·재발급뿐이므로, 「나가지 않게 하는 것」 말고 다른 방어가 없다.
 *
 * <p>이 클래스는 **문자열 마지막 방어선**이다. 1차 방어는 토큰을 애초에 URL 에 싣지 않는 것이고
 * ({@code GitHubApiClient} 는 헤더로만 보낸다), 여기는 그 전제가 깨졌을 때를 위한 그물이다.
 *
 * <p><b>범위</b> — 토큰 패턴 치환만 한다. 저장소 컨텍스트를 LLM 프롬프트에 넘기기 전의
 * 파일 단위 배제({@code .env} · {@code *.pem} · {@code credentials} 류)는 다른 문제이고
 * 이슈 #28 이 이 클래스 위에 쌓는다.
 */
public final class TokenRedactor {

    /** 마스킹 치환 문자열. 「무언가 가려졌다」가 보여야 디버깅이 가능하다. */
    public static final String MASK = "***REDACTED***";

    /**
     * 정규식 리터럴 자체가 시크릿 패턴으로 오탐되지 않도록 문자 클래스로 시작한다
     * ({@code .claude/scripts/secret-scan.sh} 가 스테이징 내용을 같은 패턴으로 훑는다).
     */
    private static final List<Pattern> TOKEN_PATTERNS = List.of(
            // GitHub PAT(classic) · OAuth · user-to-server · server-to-server · refresh
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
            // GitHub fine-grained PAT — 우리는 쓰지 않지만(Q-1) 실수로 주입될 수 있다
            Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"));

    /**
     * {@code Authorization: Bearer xxx} 처럼 헤더 형태로 실린 값.
     * 토큰 형식이 위 패턴과 달라도(예: 사내 프록시 토큰) 값 자리를 통째로 가린다.
     */
    private static final Pattern AUTHORIZATION_VALUE =
            Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(bearer|token|basic)\\s+\\S+");

    private TokenRedactor() {
    }

    /**
     * 시크릿 패턴을 마스킹한 문자열을 돌려준다. {@code null} 은 {@code null} 그대로다.
     *
     * <p>토큰이 없으면 입력을 그대로 돌려준다 — 정상 메시지를 훼손하지 않는다.
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = AUTHORIZATION_VALUE.matcher(text).replaceAll("$1$2 " + MASK);
        for (Pattern pattern : TOKEN_PATTERNS) {
            result = pattern.matcher(result).replaceAll(MASK);
        }
        return result;
    }

    /**
     * 토큰 값 자체를 로그에 남겨야 할 때의 축약형 — 앞 4자 + {@code ***}.
     *
     * <p>「어느 토큰이 쓰였나」를 구분하는 용도다. 가능하면 이것도 쓰지 말고
     * {@code hasToken()} 같은 불린만 남긴다 — {@code .claude/rules/conventions/logging.md}.
     */
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "(none)";
        }
        if (secret.length() <= 4) {
            return MASK;
        }
        return secret.substring(0, 4) + MASK;
    }
}
