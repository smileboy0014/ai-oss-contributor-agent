package com.ossagent.support.secret;

import java.util.List;
import java.util.regex.Matcher;
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
 * <p><b>범위</b> — <b>알려진 패턴</b>의 치환만 한다. 파일 단위 배제({@code .env} ·
 * {@code *.pem} · {@code credentials} 류)는 {@link SecretFilePolicy} 가 맡는다.
 * 둘은 겹치는 방어가 아니라 순서가 다른 방어다 — 키 파일에는 우리가 모르는 형식의
 * 자격증명이 얼마든지 들어 있어, <b>패턴 매칭만으로는 「가렸다」고 말할 수 없다.</b>
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

    /**
     * URL 에 박힌 자격증명 — {@code https://user:p4ssw0rd@host/path}.
     *
     * <p>이 클래스의 javadoc 이 존재 이유로 든 것이 바로 <b>예외 메시지에 실려 나오는 요청
     * URL</b> 인데, 정작 URL <b>안에</b> 자격증명이 박힌 형태를 오래 놓치고 있었다.
     * 대상 저장소의 CI 설정·문서에 흔한 모양이다.
     */
    private static final Pattern URL_CREDENTIALS =
            Pattern.compile("(?i)([a-z][a-z0-9+.\\-]*://)[^\\s/@:]+:[^\\s/@]+@");

    /**
     * PEM 계열 개인키의 시작 줄.
     *
     * <p>⚠️ <b>{@code PRIVATE KEY} 뒤에 곧바로 대시를 요구하면 PGP 가 통째로 샌다</b> —
     * {@code -----BEGIN PGP PRIVATE KEY BLOCK-----} 는 사이에 {@code  BLOCK} 이 낀다.
     * 대시와 {@code BEGIN} 사이의 공백도 마찬가지다 — RFC4716/SSH2 는
     * {@code ---- BEGIN SSH2 ENCRYPTED PRIVATE KEY ----} 로 쓴다.
     * 소문자 헤더까지 포함해 {@code (?i)} 로 받는다.
     *
     * <p>⚠️ <b>대시 반복을 {@code -+} 로 두면 그것 자체가 2차식이다.</b> 대시만 길게 이어진
     * 입력(마크다운 구분선)에서 시작 위치마다 끝까지 먹고 되돌아온다 — 실측으로 대시
     * 200,000개에 <b>3분 18초</b>였다. 상한을 둬도 탐지력은 줄지 않는다. 스캔이 시작 위치를
     * 옮겨 가므로 대시가 20개든 헤더 직전 10개가 잡히기 때문이다.
     */
    private static final Pattern PEM_HEADER =
            Pattern.compile("(?i)-{1,10} ?BEGIN [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)? ?-{0,10}");

    /** 같은 형식의 종료 줄. */
    private static final Pattern PEM_FOOTER =
            Pattern.compile("(?i)-{1,10} ?END [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)? ?-{0,10}");

    /**
     * 키 본문으로 볼 수 있는 줄 — base64 한 덩어리.
     *
     * <p>{@code -}·{@code _} 는 <b>base64url</b> 때문에 들어 있다. JWT·JWK 계열이 그 표기를 쓴다.
     */
    private static final Pattern PEM_BODY_LINE = Pattern.compile("[A-Za-z0-9+/=_-]+");

    /**
     * 🔴 <b>키 본문에 붙은 장식.</b> 이것을 벗기지 않으면 <b>헤더만 가려지고 본문이 나간다</b> —
     * 「가려졌다」고 보이는데 키는 새는, 가장 나쁜 모양이다.
     *
     * <p>키가 저장소에 실제로 나타나는 형태는 맨몸 PEM 파일이 아니다.
     *
     * <table border="1">
     *   <caption>장식이 붙는 자리</caption>
     *   <tr><td>YAML 임베드</td><td>{@code   MIIE…} — k8s Secret · Actions · Ansible. <b>1순위</b></td></tr>
     *   <tr><td>diff 문맥 줄</td><td>{@code  MIIE…} — <b>diff 가 LLM 프롬프트의 본체다</b></td></tr>
     *   <tr><td>diff 추가·삭제 줄</td><td>{@code +MIIE…} · {@code -MIIE…}</td></tr>
     *   <tr><td>마크다운 인용</td><td>{@code &gt; MIIE…}</td></tr>
     *   <tr><td>Java 문자열 연결</td><td>{@code "MIIE…\n" +}</td></tr>
     * </table>
     */
    private static final String LINE_DECORATION = " \t>+-|\"'\\";

    /** 본문 줄 뒤에 붙은 주석. */
    private static final Pattern TRAILING_COMMENT = Pattern.compile("\\s+(?:#|//).*$");

    /** {@code Proc-Type: 4,ENCRYPTED} · {@code DEK-Info: …} — 암호화된 PEM 의 머리말. */
    private static final Pattern PEM_BODY_HEADER_LINE =
            Pattern.compile("[A-Za-z][A-Za-z0-9-]*:.*");

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
        String result = redactPemBlocks(text);
        result = AUTHORIZATION_VALUE.matcher(result).replaceAll("$1$2 " + MASK);
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1" + MASK + "@");
        for (Pattern pattern : TOKEN_PATTERNS) {
            result = pattern.matcher(result).replaceAll(MASK);
        }
        return result;
    }

    /**
     * PEM 개인키 블록을 <b>줄 단위로</b> 걷어낸다.
     *
     * <h2>왜 정규식 한 방이 아닌가 — 둘 다 실측으로 드러난 결함이다</h2>
     *
     * <p><b>① 2차식 폭발.</b> 원래 {@code BEGIN…[\s\S]*?…END} 였는데, {@code END} 없는
     * {@code BEGIN} 이 여럿이면 <b>헤더마다 입력 끝까지 재스캔</b>한다. 실측으로
     * 8KB 294ms → 33KB 4.4초 → <b>131KB 76초</b>였다. GitHub 이슈 본문 상한이 65,536자이고
     * 이 코드는 {@code IssueSnapshot} 을 통해 <b>수집되는 모든 이슈</b>가 지나는 자리다.
     * 아래 스캐너는 각 문자를 한 번만 본다.
     *
     * <p><b>② 정상 본문 파괴.</b> {@code END} 가 없으면 입력 끝까지 가렸다. 그래서
     * 「{@code -----BEGIN RSA PRIVATE KEY-----} 를 커밋하지 마세요」라고 적힌 이슈 본문이
     * <b>그 지점부터 통째로 잘린 채 DB 에 영속</b>됐다. 원문 복구 경로는 없다.
     * 이제는 <b>키처럼 생긴 줄까지만</b> 먹고 멈춘다 — 산문에는 공백이 있고 base64 에는 없다.
     *
     * <p>⚠️ 과차단은 「안전한 방향」이라고만 볼 수 없다. 정상 텍스트를 망가뜨리면
     * 분석 품질이 떨어지고, 결국 <b>스크럽을 끄고 싶어진다.</b> 그것이 진짜 위험이다.
     */
    private static String redactPemBlocks(String text) {
        Matcher header = PEM_HEADER.matcher(text);
        if (!header.find()) {
            return text;
        }

        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        do {
            out.append(text, cursor, header.start()).append(MASK);
            cursor = endOfKeyBlock(text, header.end());
        } while (cursor < text.length() && header.find(cursor));

        return out.append(text, cursor, text.length()).toString();
    }

    /**
     * 헤더 바로 뒤에서 시작해 키 블록이 끝나는 위치를 찾는다.
     *
     * <p>끝은 셋 중 하나다 — 종료 줄 · 키 본문이 아닌 첫 줄의 <b>앞</b> · 입력의 끝.
     */
    private static int endOfKeyBlock(String text, int afterHeader) {
        int headerLineEnd = lineEnd(text, afterHeader);

        // 한 줄짜리 형식: 헤더와 같은 줄에 종료 표시가 온다
        Matcher footer = PEM_FOOTER.matcher(text).region(afterHeader, headerLineEnd);
        if (footer.find()) {
            return footer.end();
        }

        int cursor = headerLineEnd;
        while (cursor < text.length()) {
            int start = nextLineStart(text, cursor);
            int end = lineEnd(text, start);
            String line = text.substring(start, end);

            if (PEM_FOOTER.matcher(line).find()) {
                return end;
            }
            if (!isKeyBodyLine(line)) {
                return cursor;   // 이 줄은 남긴다 — 줄바꿈 앞에서 멈춘다
            }
            cursor = end;
        }
        return cursor;
    }

    /**
     * 키 본문으로 볼 수 있는 줄인가.
     *
     * <p>빈 줄을 허용하는 이유 — 암호화된 PEM 은 {@code Proc-Type} 머리말과 base64 본문
     * 사이에 <b>빈 줄</b>을 둔다(RFC 1421). 막으면 정작 키 본문이 그대로 나간다.
     */
    private static boolean isKeyBodyLine(String line) {
        String bare = stripDecoration(line);
        return bare.isEmpty()
                || PEM_BODY_LINE.matcher(bare).matches()
                || PEM_BODY_HEADER_LINE.matcher(bare).matches();
    }

    /**
     * 키 본문에 붙은 장식을 벗긴다 — {@link #LINE_DECORATION}.
     *
     * <p>🔴 <b>이것이 없으면 헤더만 가려지고 본문이 나간다.</b> 들여쓰기 한 칸에 키가 새는데
     * 겉보기에는 {@code ***REDACTED***} 가 찍혀 있어 <b>막혔다고 착각하게 된다.</b>
     *
     * <p>벗겨서 얻는 것과 잃는 것 — 키 블록 <b>안에서만</b> 쓰이므로, 과하게 벗겨 산문 한 줄을
     * 더 먹는 손해는 그 블록 주변에 한정된다. 반대로 벗기지 않으면 <b>키 전체</b>가 나간다.
     */
    private static String stripDecoration(String line) {
        String bare = TRAILING_COMMENT.matcher(line).replaceFirst("");
        int start = 0;
        int end = bare.length();
        while (true) {
            while (start < end && LINE_DECORATION.indexOf(bare.charAt(start)) >= 0) {
                start++;
            }
            while (end > start && LINE_DECORATION.indexOf(bare.charAt(end - 1)) >= 0) {
                end--;
            }
            // 자바 문자열 리터럴의 줄바꿈 이스케이프 — "MIIE…\n" + 형태에서 남는 꼬리
            if (end - start >= 2 && bare.charAt(end - 2) == '\\' && bare.charAt(end - 1) == 'n') {
                end -= 2;
                continue;
            }
            return bare.substring(start, end);
        }
    }

    /** {@code from} 이후 첫 줄바꿈의 위치. 없으면 입력의 끝. */
    private static int lineEnd(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return text.length();
    }

    /** 줄바꿈 위치에서 다음 줄의 시작으로. {@code \r\n} 을 한 덩어리로 본다. */
    private static int nextLineStart(String text, int lineEnd) {
        if (lineEnd < text.length() && text.charAt(lineEnd) == '\r'
                && lineEnd + 1 < text.length() && text.charAt(lineEnd + 1) == '\n') {
            return lineEnd + 2;
        }
        return Math.min(lineEnd + 1, text.length());
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
