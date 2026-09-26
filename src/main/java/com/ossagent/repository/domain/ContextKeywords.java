package com.ossagent.repository.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이슈 텍스트 → {@link ContextKeyword} 집합. <b>순수 함수다</b> — 이슈 #15 FR-1.
 *
 * <h2>왜 LLM 을 부르지 않는가</h2>
 * Java/Spring 이슈의 신호는 규칙적이다 — 스택트레이스 프레임, {@code CamelCase} 타입 이름,
 * {@code src/main/java/...} 경로. 여기에 모델을 한 번 더 태우면 <b>비용과 비결정성이 둘 다</b>
 * 늘어난다. 이 제품의 품질 축은 「나쁜 결과를 걸러내는가」(PRD §30)이고, 결정적 단계는
 * 테스트로 고정된다 — PLAN-15 D-3.
 *
 * <h2>🔴 입력에 경계를 둔다</h2>
 * {@link #MAX_SCAN_CHARS} 만큼만 훑는다. 이슈 본문은 <b>대상 저장소 사람이 쓴 것</b>이라
 * 길이가 우리 통제 밖이고, 붙여 넣은 로그 수만 줄짜리 이슈가 실제로 있다.
 *
 * <p>⚠️ 「정규식이 선형이라 괜찮다」고 <b>적지 않는다.</b> 측정하지 않았다.
 * 측정 대신 <b>입력에 상한</b>을 둔다 — {@code testing-philosophy.md} 가
 * 「성능·복잡도 주장은 측정하지 않으면 적지 않는다」로 못 박은 자리다.
 */
public final class ContextKeywords {

    /**
     * 훑는 길이의 상한. 넘는 부분은 보지 않는다.
     *
     * <p>이슈 앞부분에 핵심이 오고 뒤는 붙여 넣은 로그인 경우가 대부분이라 손실이 작다.
     * {@code agent.analysis.max-body-chars}(20,000)보다 넉넉하게 잡았다 — 그쪽은 모델에
     * 보내는 예산이고 여기는 우리가 읽기만 하는 비용이다.
     */
    static final int MAX_SCAN_CHARS = 50_000;

    /** 타입 이름·경로로 인정하는 최소 길이 */
    private static final int MIN_TYPE_LENGTH = 3;

    /** 낱말로 인정하는 최소 길이 */
    private static final int MIN_TERM_LENGTH = 4;

    /**
     * 소스로 볼 확장자. 경로 리터럴 판정에 쓴다.
     *
     * <p>⚠️ 여기 없는 확장자는 경로 리터럴로 <b>인정되지 않는다</b>(낱말로 떨어진다).
     * 넓히는 것은 안전하지만, 넓힐 때 {@code png}·{@code jar} 같은 바이너리를 넣지 않는다 —
     * 프롬프트에 실을 수 없는 것을 후보로 올리면 호출 예산만 태운다.
     */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "groovy", "scala",
            "xml", "gradle", "properties", "yml", "yaml", "json",
            "md", "adoc", "txt", "sql");

    /** {@code src/main/java/org/x/Foo.java} · {@code Foo.java} — 경로처럼 생긴 토큰 */
    private static final Pattern PATH_LIKE =
            Pattern.compile("[A-Za-z0-9_./\\-]*[A-Za-z0-9_\\-]\\.([A-Za-z0-9]{1,10})\\b");

    /**
     * {@code org.springframework.kafka.listener.KafkaMessageListenerContainer} —
     * 소문자 패키지 뒤에 대문자 타입.
     *
     * <p>스택트레이스 프레임({@code at com.x.Foo.bar(Foo.java:12)})도 여기 걸린다.
     * 별도 규칙을 두지 않는 이유 — 프레임에서 우리가 쓰는 것은 결국 패키지와 타입이고,
     * 파일 이름은 {@link #PATH_LIKE} 가 이미 잡는다.
     */
    private static final Pattern QUALIFIED_TYPE = Pattern.compile(
            "\\b([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+)\\.([A-Z][A-Za-z0-9_]*)\\b");

    /**
     * {@code KafkaTemplate} — 대문자가 <b>둘 이상</b>인 진짜 CamelCase.
     *
     * <p>🔴 대문자 하나로 만족시키면 영어 문장의 첫 낱말({@code The} · {@code When})과
     * 제품 이름({@code Kafka})이 전부 타입으로 잡힌다. 그런 값이 수십 개 섞이면
     * 점수가 평평해져 <b>진짜 타입 이름의 변별력이 사라진다.</b>
     *
     * <p>단어 하나짜리 타입({@code Foo})은 이 규칙에서 빠지지만, 백틱 안에 있으면
     * {@link #BACKTICKED} 가 건져 올린다.
     */
    private static final Pattern CAMEL_TYPE =
            Pattern.compile("\\b([A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+)\\b");

    /** 백틱·코드펜스 안의 토큰. 사람이 일부러 표시한 것이라 한 단계 강하게 본다 */
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]{1,200})`");

    /** 낱말 */
    private static final Pattern WORD = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_\\-]{2,})\\b");

    /**
     * 불용어 — 영어 상용어 + 이슈 상투어.
     *
     * <p>이것을 거르지 않으면 {@code should}·{@code issue} 같은 낱말이 모든 이슈에 나타나
     * <b>어떤 저장소에서도 같은 파일이 뜬다.</b> 신호가 아니라 배경이다.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her",
            "was", "one", "our", "out", "has", "him", "his", "how", "its", "new", "now", "old",
            "see", "two", "way", "who", "did", "get", "may", "use", "this", "that", "with",
            "from", "have", "been", "were", "will", "would", "should", "could", "when", "then",
            "than", "them", "they", "there", "their", "what", "which", "while", "about", "after",
            "before", "because", "into", "over", "such", "some", "only", "also", "more", "most",
            "other", "same", "like", "just", "does", "doesn", "don",
            // 이슈 상투어
            "issue", "issues", "problem", "bug", "error", "expected", "actual", "behavior",
            "behaviour", "steps", "reproduce", "version", "example", "description", "fix",
            "please", "thanks", "hello", "code", "line", "lines", "file", "files", "test",
            "tests", "case", "cases", "using", "used", "want", "need", "make", "made", "work",
            "works", "working", "following", "above", "below", "here", "however", "since");

    private ContextKeywords() {
    }

    /**
     * 이슈에서 키워드를 뽑는다.
     *
     * <p>같은 값이 여러 종류로 잡히면 <b>가장 강한 종류 하나만</b> 남긴다 — 예를 들어
     * {@code KafkaTemplate} 이 패키지 경로 안에서도 잡히고 본문에서도 잡히면 한 번만 센다.
     * 중복을 그대로 두면 본문에 여러 번 나온 낱말이 종류를 이기게 된다.
     *
     * @param issue        분석 대상. {@code title}·{@code body} 를 본다
     * @param maxKeywords  상한. 넘으면 <b>강한 것부터</b> 남긴다 — 약한 낱말이 잘린다
     */
    public static List<ContextKeyword> from(AnalyzableIssueText issue, int maxKeywords) {
        if (issue == null) {
            return List.of();
        }
        if (maxKeywords < 1) {
            throw new IllegalArgumentException("maxKeywords 는 1 이상이어야 합니다: " + maxKeywords);
        }

        String text = scannable(issue.title(), issue.body());
        if (text.isBlank()) {
            return List.of();
        }

        // 값 → 가장 강한 종류. LinkedHashMap 이라 같은 세기끼리는 등장 순서가 유지된다
        Map<String, ContextKeyword> strongest = new LinkedHashMap<>();
        collectBackticked(text, strongest);
        collectPaths(text, strongest);
        collectQualifiedTypes(text, strongest);
        collectCamelTypes(text, strongest);
        collectTerms(text, strongest);

        List<ContextKeyword> ordered = new ArrayList<>(strongest.values());
        // 🔴 정렬이 결정적이어야 한다 — 같은 입력에 같은 선별이 나와야 하기 때문이다(NFR-4).
        //    가중치가 같으면 값 사전순으로 깬다
        ordered.sort(Comparator.comparingInt(ContextKeyword::weight).reversed()
                .thenComparing(ContextKeyword::normalized));
        return ordered.size() <= maxKeywords ? List.copyOf(ordered)
                : List.copyOf(ordered.subList(0, maxKeywords));
    }

    /** 제목과 본문을 이어 붙이고 상한까지 자른다. 제목이 앞이라 잘려도 남는다 */
    private static String scannable(String title, String body) {
        String joined = (title == null ? "" : title) + "\n" + (body == null ? "" : body);
        return joined.length() <= MAX_SCAN_CHARS ? joined : joined.substring(0, MAX_SCAN_CHARS);
    }

    private static void collectBackticked(String text, Map<String, ContextKeyword> out) {
        Matcher matcher = BACKTICKED.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            if (token.isEmpty() || token.length() < MIN_TYPE_LENGTH) {
                continue;
            }
            // 백틱 안은 사람이 일부러 표시한 것이다. 경로면 경로로, 아니면 타입으로 본다 —
            // 단어 하나짜리 타입(Foo)이 CAMEL_TYPE 에서 빠지는 것을 여기서 건진다
            if (isPathLiteral(token)) {
                put(out, token, ContextKeyword.Kind.PATH_LITERAL);
            } else if (token.matches("[A-Za-z][A-Za-z0-9_]*")) {
                put(out, token, ContextKeyword.Kind.TYPE_NAME);
            }
        }
    }

    private static void collectPaths(String text, Map<String, ContextKeyword> out) {
        Matcher matcher = PATH_LIKE.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (isPathLiteral(token)) {
                put(out, token, ContextKeyword.Kind.PATH_LITERAL);
            }
        }
    }

    private static void collectQualifiedTypes(String text, Map<String, ContextKeyword> out) {
        Matcher matcher = QUALIFIED_TYPE.matcher(text);
        while (matcher.find()) {
            put(out, matcher.group(1), ContextKeyword.Kind.PACKAGE);
            put(out, matcher.group(2), ContextKeyword.Kind.TYPE_NAME);
        }
    }

    private static void collectCamelTypes(String text, Map<String, ContextKeyword> out) {
        Matcher matcher = CAMEL_TYPE.matcher(text);
        while (matcher.find()) {
            put(out, matcher.group(1), ContextKeyword.Kind.TYPE_NAME);
        }
    }

    private static void collectTerms(String text, Map<String, ContextKeyword> out) {
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.length() < MIN_TERM_LENGTH) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (STOP_WORDS.contains(lower)) {
                continue;
            }
            put(out, lower, ContextKeyword.Kind.TERM);
        }
    }

    /**
     * 경로 리터럴인가 — <b>아는 소스 확장자로 끝나야</b> 한다.
     *
     * <p>확장자를 확인하지 않으면 {@code e.g} · {@code spring.io} · 버전 번호({@code 3.2.1})가
     * 전부 경로가 되어 가장 높은 가중치를 가져간다.
     */
    private static boolean isPathLiteral(String token) {
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }
        String extension = token.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SOURCE_EXTENSIONS.contains(extension);
    }

    /** 이미 더 강한 종류로 들어 있으면 덮어쓰지 않는다 */
    private static void put(Map<String, ContextKeyword> out, String value,
            ContextKeyword.Kind kind) {
        if (value == null || value.isBlank()) {
            return;
        }
        String key = value.toLowerCase(Locale.ROOT);
        ContextKeyword existing = out.get(key);
        if (existing == null || kind.weight() > existing.weight()) {
            out.put(key, new ContextKeyword(value, kind));
        }
    }

    /**
     * 키워드 추출이 필요로 하는 것만 추린 입력.
     *
     * <p>🔴 {@code issue.domain.AnalyzableIssue} 를 직접 받지 않는 이유 —
     * 이 클래스는 <b>순수 판정</b>이라 남의 애그리거트 값 타입에 묶일 이유가 없고,
     * 테스트가 제목·본문 두 줄로 돌아간다. 변환은 UseCase 가 한다.
     */
    public record AnalyzableIssueText(String title, String body) {
    }
}
