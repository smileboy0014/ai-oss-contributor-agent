package com.ossagent.pullrequest.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 대상 저장소 Fork 안의 브랜치 이름 — {@code oss-agent/issue-{issueNumber}-{short-description}}.
 *
 * <p>규칙의 출처는 <b>PRD §14</b> 이고, 우리 저장소의 브랜치 컨벤션
 * ({@code feature/12_slug})과 <b>다르다.</b> 섞지 않는다 — {@code git-workflow.md}.
 *
 * <h2>🔴 short-description 은 외부에서 온 문자열이다</h2>
 *
 * <p>이슈 제목이나 LLM 이 만든 요약이 흘러든다. 그대로 이으면 브랜치 이름이
 * <b>ref 경로</b>로 조립되는 자리에서 의미가 바뀐다.
 *
 * <table border="1">
 *   <caption>정규화하지 않으면</caption>
 *   <tr><th>입력</th><th>결과</th></tr>
 *   <tr><td>{@code ../../heads/main}</td>
 *       <td>{@code refs/heads/oss-agent/issue-1-../../heads/main} 이 <b>다른 ref 로 정규화</b>된다</td></tr>
 *   <tr><td>{@code x@{0}} · {@code x~1} · {@code x^}</td>
 *       <td>git revision 문법이다. ref 로 쓸 수 없거나 다른 것을 가리킨다</td></tr>
 *   <tr><td>{@code foo.lock}</td><td>git 이 예약한 접미사다</td></tr>
 * </table>
 *
 * <p>그래서 <b>거부목록으로 위험 문자를 빼지 않는다.</b> 허용 문자만 남기는
 * <b>여집합</b> 방식이다 — {@code testing-philosophy.md} 「거부목록으로 방어하지 않는다」.
 * 새 위험 문자가 생겨도 허용 목록에 없으면 자동으로 걸러진다.
 */
public record BranchName(String value) {

    /** {@code oss-agent/issue-{n}-} 뒤에 붙는 부분의 상한. */
    static final int MAX_SLUG_LENGTH = 40;

    /** git ref 전체 길이 상한은 훨씬 크지만, 읽을 수 있는 선에서 자른다. */
    static final int MAX_LENGTH = 120;

    private static final String PREFIX = "oss-agent/issue-";

    /** 🔴 허용 문자만 남긴다. 이 밖은 전부 구분자로 접힌다. */
    private static final Pattern NOT_ALLOWED = Pattern.compile("[^a-z0-9]+");

    private static final Pattern EXPECTED = Pattern.compile("oss-agent/issue-[0-9]+(-[a-z0-9-]*[a-z0-9])?");

    public BranchName {
        if (value == null || !EXPECTED.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "브랜치 이름이 PRD §14 형식이 아닙니다(oss-agent/issue-{n}-{slug}): " + value);
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("브랜치 이름이 %d 자를 넘습니다: %d"
                    .formatted(MAX_LENGTH, value.length()));
        }
    }

    /**
     * 이슈 번호와 자유 문자열에서 만든다.
     *
     * @param issueNumber      대상 저장소의 이슈 번호. 1 이상
     * @param shortDescription 외부에서 온 자유 문자열. <b>정규화된다</b>
     */
    public static BranchName of(int issueNumber, String shortDescription) {
        if (issueNumber < 1) {
            throw new IllegalArgumentException("이슈 번호는 1 이상이어야 합니다: " + issueNumber);
        }
        String slug = slugify(shortDescription);
        return new BranchName(slug.isEmpty() ? PREFIX + issueNumber : PREFIX + issueNumber + "-" + slug);
    }

    /**
     * 허용 문자만 남기고 나머지는 {@code -} 로 접는다.
     *
     * <p>🔴 <b>빈 문자열이 되는 것을 허용한다.</b> 설명이 전부 비 ASCII 인 경우
     * (한국어 이슈 제목)가 실제로 흔한데, 거기서 예외를 던지면 <b>정상 이슈가 브랜치를
     * 못 만든다.</b> 이름은 사람이 읽기 위한 것이고 식별은 이슈 번호가 한다.
     */
    private static String slugify(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = NOT_ALLOWED.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("-");
        String trimmed = strip(folded);
        if (trimmed.length() > MAX_SLUG_LENGTH) {
            trimmed = strip(trimmed.substring(0, MAX_SLUG_LENGTH));
        }
        return trimmed;
    }

    private static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }

    /** {@code refs/heads/...} — Git Data API 의 ref 생성이 요구하는 표기. */
    public String refName() {
        return "refs/heads/" + value;
    }

    @Override
    public String toString() {
        return value;
    }
}
