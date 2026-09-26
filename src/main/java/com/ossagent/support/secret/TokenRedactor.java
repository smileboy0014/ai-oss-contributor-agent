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
     * 키 블록의 <b>머리말 줄</b> — 알려진 태그만 받는다.
     *
     * <p>「콜론이 있는 줄」을 기준으로 삼으면 {@code title: hello} 같은 <b>평범한 산문</b>이
     * 키 본문으로 먹힌다. 반대로 목록을 너무 좁히면 <b>키가 통째로 샌다</b> —
     * {@code Version} 을 빠뜨렸더니 PGP armor 가 전부 빠져나갔다. 규격에서 가져온다:
     * RFC 4880 armor({@code Version}·{@code Comment}·{@code MessageID}·{@code Hash}·
     * {@code Charset}) + RFC 1421 PEM({@code Proc-Type}·{@code DEK-Info}·
     * {@code Originator-*}·{@code Recipient-*}·{@code Subject}).
     */
    private static final Pattern PEM_BODY_HEADER_LINE = Pattern.compile(
            "(?i)(?:Version|Comment|MessageID|Hash|Charset"
                    + "|Proc-Type|DEK-Info|Subject"
                    + "|Originator-[A-Za-z-]+|Recipient-[A-Za-z-]+):.*");

    /**
     * 블록에 <b>들어갈</b> 때 요구하는 최소 본문 런 길이.
     *
     * <p>🔴 <b>과차단을 막는 손잡이가 여기 하나로 모인다.</b> 산문에 적힌 헤더 언급
     * (「{@code -----BEGIN …-----} 를 커밋하지 마세요」) 뒤에 오는 {@code NORMAL-2} ·
     * {@code TODO} · {@code - item} 같은 짧은 줄이 키 본문으로 먹히던 것을 이 조건이 끊는다.
     *
     * <p>실제 PEM·PGP 본문 줄은 <b>64~70자</b>다. 20 으로 두었더니
     * {@code InternationalizationHelper}(26자) 같은 긴 식별자 한 줄이 먹혔다.
     * {@link #ENTRY_WINDOW_LINES} 가 짧은 첫 줄을 건너뛰어 주므로 올려도 안전하다.
     */
    private static final int MIN_KEY_BODY_LENGTH = 40;

    /**
     * 🔴 진입 자격을 <b>바로 다음 한 줄</b>이 아니라 <b>창(window)</b>으로 본다.
     *
     * <p>한 줄만 보면 그 줄이 자격에 못 미치는 순간 <b>블록 전체가 샌다.</b> 실제로 그랬다 —
     * gpg 2.1+ 는 {@code Version:} 을 생략해 헤더 다음이 <b>빈 줄</b>이고, 그것으로 PGP 개인키
     * 전체가 빠져나갔다. 첫 본문 줄이 짧기만 해도 뒤의 64자 줄들이 전부 샜다.
     *
     * <p>창 안의 <b>빈 줄과 머리말은 통과만 시키고 자격은 주지 않는다.</b> 자격 줄을 하나도
     * 만나지 못하면 <b>통과시킨 줄까지 되돌려</b> 블록에 들어가지 않는다 — 산문을 먹지 않기
     * 위해서다.
     */
    private static final int ENTRY_WINDOW_LINES = 5;

    /**
     * 본문 줄에 붙을 수 있는 <b>장식의 기본 예산</b>. 헤더 줄이 더 깊이 들여쓰여 있으면
     * {@link #decorationBudget} 이 그만큼 늘린다.
     *
     * <p>🔴 <b>장식을 열거하지 않는다.</b> 예전에는 벗길 문자를 나열했는데, 그러면
     * {@code  * }(Javadoc) · {@code # }(셸) · {@code 1. }(번호 목록) · 줄 끝 {@code ;} ·
     * {@code ,} 처럼 <b>목록에 없는 형태마다 구멍이 새로 난다.</b> 세 번 연속 그렇게 깨졌다.
     *
     * <p>대신 <b>여집합</b>으로 본다 — 줄에서 가장 긴 키 문자 런을 찾고, 나머지가 예산
     * 안이면 그 런이 본문이다. 장식의 <b>모양</b>을 몰라도 <b>양</b>으로 판정할 수 있다.
     */
    private static final int BASE_DECORATION_BUDGET = 8;

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
            cursor = endOfKeyBlock(text, header.end(), decorationBudget(text, header.start()));
        } while (cursor < text.length() && header.find(cursor));

        return out.append(text, cursor, text.length()).toString();
    }

    /**
     * 장식 예산을 <b>헤더 줄에서 유도한다</b> — 상수로 두지 않는다.
     *
     * <p>🔴 상수 8 로 두었더니 <b>들여쓰기 9칸부터 키가 샜다.</b> k8s Secret·Helm·Actions 는
     * 8~12칸이 일상이고, 그 파일의 diff 는 접두어가 붙어 1이 더 늘어난다.
     *
     * <p>본문은 헤더와 <b>같은 장식을 달고 있다</b> — YAML 이든 diff 든 그 줄만 따로 들여쓰지
     * 않는다. 그래서 헤더가 달고 있는 만큼을 예산으로 주면 매직넘버가 사라지고, 얼마나 깊이
     * 들여쓰든 따라간다. {@code +2} 는 본문 줄에만 붙는 꼬리({@code ,} · {@code ;})의 몫이다.
     */
    private static int decorationBudget(String text, int headerStart) {
        int lineStart = headerStart;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        return Math.max(BASE_DECORATION_BUDGET, headerStart - lineStart + 2);
    }

    /**
     * 헤더 바로 뒤에서 시작해 키 블록이 끝나는 위치를 찾는다.
     *
     * <p>끝은 셋 중 하나다 — 종료 줄 · 키 본문이 아닌 첫 줄의 <b>앞</b> · 입력의 끝.
     */
    private static int endOfKeyBlock(String text, int afterHeader, int budget) {
        int headerLineEnd = lineEnd(text, afterHeader);

        // 한 줄짜리 형식: 헤더와 같은 줄에 종료 표시가 온다
        Matcher footer = PEM_FOOTER.matcher(text).region(afterHeader, headerLineEnd);
        if (footer.find()) {
            return footer.end();
        }

        int cursor = headerLineEnd;
        int beforeWindow = headerLineEnd;   // 진입에 실패하면 여기까지만 가린다
        boolean entered = false;
        int probed = 0;

        while (cursor < text.length()) {
            int start = nextLineStart(text, cursor);
            int end = lineEnd(text, start);
            String line = text.substring(start, end);

            if (PEM_FOOTER.matcher(line).find()) {
                return end;
            }

            if (entered) {
                if (!continuesKeyBody(line, budget)) {
                    return cursor;   // 이 줄은 남긴다 — 줄바꿈 앞에서 멈춘다
                }
            } else if (startsKeyBody(line, budget)) {
                entered = true;
            } else if (continuesKeyBody(line, budget) && ++probed <= ENTRY_WINDOW_LINES) {
                // 빈 줄·머리말·짧은 본문 줄은 지나가게 두되 자격은 주지 않는다.
                // 끝내 자격 줄을 못 만나면 beforeWindow 로 되돌아가 이 줄들을 남긴다
            } else {
                return beforeWindow;
            }
            cursor = end;
        }
        return entered ? cursor : beforeWindow;
    }

    /**
     * 진입 자격은 없지만 <b>지나가도 되는</b> 줄인가 — 빈 줄과 머리말.
     *
     * <p>gpg 2.1+ 는 {@code Version:} 을 생략해 헤더 다음이 <b>빈 줄</b>이고,
     * 암호화된 PEM 은 {@code Proc-Type} 뒤에 빈 줄을 둔다(RFC 1421).
     * 여기서 끊으면 그 아래 키 본문이 통째로 나간다.
     */
    private static boolean passesThroughToEntry(String line) {
        String bare = stripComment(line);
        return bare.isBlank() || PEM_BODY_HEADER_LINE.matcher(bare.strip()).matches();
    }

    /**
     * 🔴 <b>블록에 들어가도 되는가</b> — 과차단을 막는 문턱이다.
     *
     * <p>헤더가 <b>산문에서 언급</b>됐을 뿐인 경우가 흔하다(「이 헤더를 커밋하지 마세요」).
     * 그때 뒤따르는 줄을 본문으로 먹으면 정상 텍스트가 사라지고, 그 값이 DB 에 영속된다.
     * 그래서 <b>실제 키 본문만큼 긴 줄</b> 또는 <b>PEM 머리말</b>이 와야 들어간다.
     */
    private static boolean startsKeyBody(String line, int budget) {
        int[] shape = shapeOf(line);
        return shape[0] >= MIN_KEY_BODY_LENGTH && shape[1] <= budget;
    }

    /**
     * 블록 <b>안에서</b> 계속 본문으로 볼 줄인가.
     *
     * <p>들어온 뒤에는 느슨하게 본다 — base64 마지막 줄은 패딩만 남아 짧고, 암호화된 PEM 은
     * 머리말과 본문 사이에 <b>빈 줄</b>을 둔다(RFC 1421). 여기서 끊으면 정작 키가 나간다.
     * 문턱은 {@link #startsKeyBody} 한 곳에만 둔다.
     */
    private static boolean continuesKeyBody(String line, int budget) {
        if (passesThroughToEntry(line)) {
            return true;
        }
        int[] shape = shapeOf(line);
        return shape[0] >= 1 && shape[1] <= budget;
    }

    /**
     * 줄의 <b>모양</b> — {@code [가장 긴 키 문자 런, 나머지 길이]}.
     *
     * <p>장식의 모양을 열거하지 않고 <b>양</b>으로 판정하기 위한 것이다. 각 문자를 한 번만
     * 본다 — 정규식 greedy 수량자를 이 경로에서 없앤 이유가 그것이다(세 번 연속 거기서
     * 2차식이 났다).
     */
    private static int[] shapeOf(String line) {
        String bare = stripComment(line);
        int longest = 0;
        int run = 0;
        for (int i = 0; i < bare.length(); i++) {
            if (isKeyChar(bare.charAt(i))) {
                run++;
                longest = Math.max(longest, run);
            } else {
                run = 0;
            }
        }
        return new int[] {longest, bare.length() - longest};
    }

    /** base64 와 base64url({@code -}·{@code _})에 쓰이는 문자. */
    private static boolean isKeyChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '+' || c == '/' || c == '=' || c == '_' || c == '-';
    }

    /**
     * 줄 끝 주석을 잘라낸다 — {@code MIIE… # 운영 키}.
     *
     * <p>⚠️ <b>정규식을 쓰지 않는다.</b> {@code \s+(?:#|//)} 로 두었더니 공백만 긴 줄에서
     * 2차식이 됐다 — 실측 공백 128,000개에 1분 45초. {@code -+} 를 고치자 같은 실패가
     * {@code \s+} 로 옮겨갔을 뿐이었다. 인덱스 스캔은 그 자리가 없다.
     */
    private static String stripComment(String line) {
        for (int i = 1; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean marker = c == '#'
                    || (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/');
            if (marker && (line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t')) {
                return line.substring(0, i);
            }
        }
        return line;
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
