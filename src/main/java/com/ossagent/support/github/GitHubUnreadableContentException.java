package com.ossagent.support.github;

/**
 * 응답은 200 인데 <b>내용을 쓸 수 없는</b> 경우.
 *
 * <p>이 타입이 따로 있는 이유는 S-5 다. 기여 규약 판정에서 「읽지 못했다」가
 * 「규약이 없다」로 번역되면 <b>규약 위반 PR 이 나간다.</b>
 * {@code safety-boundaries.md} S-5 는 「정책 파싱 실패는 「허용」이 아니라 「보류」다」라고 못 박는다.
 * 그래서 조용한 빈 값 대신 예외로 떨어뜨려 호출자가 보류를 선택하게 만든다.
 *
 * <p>실제로 걸리는 경우 —
 * <ul>
 *   <li><b>1MB 초과 파일</b>: Contents API 는 {@code content} 를 빈 문자열로 내려준다.
 *       읽으려면 blob/raw API 로 가야 한다
 *   <li>경로가 <b>디렉터리</b>라 JSON 배열이 온다
 *   <li>{@code type} 이 {@code symlink} · {@code submodule} 이다
 * </ul>
 */
public class GitHubUnreadableContentException extends GitHubApiException {

    public GitHubUnreadableContentException(String message) {
        super(200, message);
    }
}
