package com.ossagent.repository.domain;

import java.util.Locale;
import java.util.Set;

/**
 * 대상 저장소가 준 경로를 <b>우리 호출에 쓸 수 있는가</b> 판정한다.
 *
 * <h2>🔴 왜 따로 있는가 — 여기가 첫 자리다</h2>
 * 이슈 #15 는 <b>대상 저장소가 통제하는 문자열이 우리 URL 경로로 들어가는 첫 단계</b>다.
 * #7(규약 수집)은 우리가 정한 고정 경로 목록만 읽어 이 문제가 없었다.
 * {@code GitHubRequest} 의 검증은 「{@code /} 로 시작하는가」뿐이라 그 아래를 보지 않는다.
 *
 * <p>트리를 준 것이 GitHub 자신이라 악의적 경로가 올 가능성은 낮다. 그러나
 * <b>「낮다」에 기대지 않는다</b> — {@code safety-boundaries.md} 가 S-1 에서
 * 「없는 권한에 기대지 않는다」로 세워 둔 것과 같은 태도다. 판정은 한 줄이고,
 * 빠졌을 때 드러나는 방식이 조용하다.
 *
 * <p>여기에 <b>소스인가</b> 판정도 함께 둔다. 둘 다 「이 경로를 후보로 올릴 것인가」라는
 * 같은 물음이고, 가르면 호출자가 두 번 물어야 한다.
 */
public final class RepositoryPathPolicy {

    /** 경로 전체 길이 상한. GitHub 이 이보다 긴 경로를 주는 일은 없다 */
    private static final int MAX_PATH_LENGTH = 400;

    /**
     * 프롬프트에 실을 만한 텍스트 확장자.
     *
     * <p>🔴 <b>허용 목록이다.</b> 「막을 것을 열거」하면 열거에 없는 형태마다 구멍이 새로 난다 —
     * {@code testing-philosophy.md} 가 「거부목록으로 방어하지 않는다」로 정리한 자리다.
     * 바이너리(`png`·`jar`·`so`)를 하나씩 막는 대신, <b>읽을 수 있다고 아는 것만</b> 통과시킨다.
     */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "groovy", "scala",
            "xml", "gradle", "properties", "yml", "yaml", "json",
            "md", "adoc", "txt", "sql");

    /**
     * 후보에서 빼는 디렉터리 — 빌드 산출물·의존성·VCS 메타.
     *
     * <p>여기 있는 것은 대상 저장소가 <b>커밋해 두었더라도</b> 이슈와 무관하다.
     * 생성물을 고쳐 봐야 다음 빌드에 지워진다.
     */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "build", "out", "target", "bin", "node_modules", ".git", ".gradle", ".idea", ".mvn");

    private RepositoryPathPolicy() {
    }

    /**
     * 이 경로를 우리 호출에 넣어도 되는가 — <b>모양</b>만 본다.
     *
     * <p>판정은 fail-closed 다. 애매하면 배제한다 — 파일 하나를 덜 보는 손해는
     * 되돌릴 수 있고, 엉뚱한 URL 을 부르는 손해는 그렇지 않다.
     */
    public static boolean isSafe(String path) {
        if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH) {
            return false;
        }
        // 절대경로·스킴·호스트로 읽힐 수 있는 모양을 거부한다. 경로 조립에 그대로 들어간다
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("://")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            // 제어문자·공백은 URL 조립과 로그 양쪽에서 문제를 만든다(줄바꿈 = 로그 인젝션)
            if (c < 0x21 || c == 0x7F) {
                return false;
            }
        }
        // 🔴 여집합으로 본다 — 「.. 라는 문자열이 있는가」가 아니라 「.. 세그먼트가 있는가」.
        //    문자열 검사는 "foo..bar" 를 무고하게 막고 "a/%2e%2e/b" 를 놓친다
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /** 내용을 읽어 프롬프트에 실을 만한 파일인가 */
    public static boolean isSelectableSource(RepositoryTreeEntry entry) {
        if (entry == null || !entry.isBlob() || !isSafe(entry.path())) {
            return false;
        }
        if (!SOURCE_EXTENSIONS.contains(entry.extension())) {
            return false;
        }
        String normalized = entry.path().toLowerCase(Locale.ROOT);
        for (String segment : normalized.split("/", -1)) {
            if (EXCLUDED_DIRECTORIES.contains(segment)) {
                return false;
            }
        }
        return true;
    }
}
