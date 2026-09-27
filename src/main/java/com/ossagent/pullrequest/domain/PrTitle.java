package com.ossagent.pullrequest.domain;

import com.ossagent.support.ExternalText;
import com.ossagent.support.secret.TokenRedactor;

/**
 * Draft PR 의 제목 — <b>대상 저장소에 나가는 텍스트</b>.
 *
 * <h2>🔴 재료가 외부에서 온다</h2>
 *
 * <p>이슈 제목({@code AnalyzableIssue.title})이 그대로 흘러든다. 그것은 대상 저장소가 쓴
 * 문자열이고, 우리는 그 안에 무엇이 있는지 모른다. 그래서 <b>compact 생성자가 스크럽을
 * 강제</b>한다 — {@code String} 을 받는 다른 생성 경로를 두지 않는다({@code SelectedFile} 선례).
 *
 * <p>⚠️ <b>우리 커밋 컨벤션을 여기에 적용하지 않는다</b>(S-5). {@code feat(scope):} 형식은
 * 이 저장소 안에서만 유효하고, 대상 저장소에 강요하는 코드가 있으면 반려다 —
 * {@code commit-convention.md}. 제목은 <b>이슈 제목을 그대로</b> 쓰고 이슈 번호만 덧붙인다.
 *
 * <h2>제어문자를 지운다</h2>
 *
 * <p>제목은 GitHub UI·메일 알림·커밋 목록에 <b>한 줄로</b> 렌더링된다. 줄바꿈이 들어가면
 * 뒤가 잘리거나 다른 필드처럼 보인다. 🔴 <b>거부목록이 아니라 여집합</b>이다 — 「어떤 제어
 * 문자가 위험한가」를 세지 않고 <b>제어문자 전체</b>를 공백으로 접는다.
 */
public record PrTitle(@ExternalText(ExternalText.Source.TARGET_REPOSITORY) String value) {

    /** GitHub 은 256 자를 넘으면 자른다. 잘린 제목보다 우리가 자른 제목이 낫다. */
    public static final int MAX_LENGTH = 256;

    private static final String ELLIPSIS = "…";

    public PrTitle {
        if (value == null || value.isBlank()) {
            throw new DraftPrException("PR 제목이 비어 있습니다");
        }
        // 🔴 스크럽이 먼저다. 자른 뒤에 스크럽하면 토큰이 경계에서 반쪽만 남아
        //    패턴에 걸리지 않는다 — 「잘린 시크릿은 시크릿이 아니다」가 아니다
        value = TokenRedactor.redact(value);
        value = collapseControlCharacters(value).trim();
        if (value.isEmpty()) {
            throw new DraftPrException("PR 제목이 제어문자뿐입니다");
        }
        if (value.length() > MAX_LENGTH) {
            value = value.substring(0, MAX_LENGTH - ELLIPSIS.length()) + ELLIPSIS;
        }
    }

    /**
     * 이슈 제목에서 만든다.
     *
     * @param issueNumber 대상 저장소의 이슈 번호
     * @param issueTitle  대상 저장소가 쓴 제목. <b>스크럽된다</b>
     */
    public static PrTitle forIssue(int issueNumber, String issueTitle) {
        if (issueNumber < 1) {
            throw new DraftPrException("이슈 번호는 1 이상이어야 합니다: " + issueNumber);
        }
        String base = issueTitle == null || issueTitle.isBlank()
                ? "Address issue #" + issueNumber
                : issueTitle;
        return new PrTitle(base + " (#" + issueNumber + ")");
    }

    private static String collapseControlCharacters(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c)) {
                pendingSpace = !out.isEmpty();
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return value;
    }
}
