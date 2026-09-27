package com.ossagent.pullrequest.domain;

/**
 * Fork 에 올릴 파일 1건의 변경.
 *
 * <h2>왜 내용을 값으로 들고 오는가</h2>
 *
 * <p>이 도메인은 <b>워크스페이스를 읽지 않는다.</b> 읽는 주체는 #18 이고, 여기는 전송만 한다.
 * 그렇게 가른 이유는 {@code SandboxWorkspace} 검증과 {@code SecretFilePolicy} 배선을
 * <b>한 벌 더 갖지 않기 위해서</b>다 — 같은 방어가 두 곳에 있으면 한쪽이 느슨해질 때 드러나지 않는다.
 *
 * <p>🔴 <b>그 대가로 이 경로에는 내용 검사가 없다.</b> 여기 실린 내용은 <b>공개 Fork 에
 * 영구 게시</b>된다. 대상 저장소가 커밋해 둔 시크릿이나 LLM 이 넣은 문자열이 섞여 있어도
 * 이 타입은 알지 못한다 — 그것을 거르는 것은 #18 의 책임이다(S-4 잔여 위험).
 *
 * <h2>⚠️ 텍스트만 다룬다</h2>
 *
 * <p>내용을 {@code String} 으로 받는다. 우리가 만드는 변경은 Java 소스·설정 파일이라
 * 지금은 충분하지만, <b>바이너리 파일은 표현할 수 없다.</b> 필요해지면
 * {@code byte[]} + {@code encoding} 으로 넓혀야 하고, 그때 GitHub blob API 의
 * {@code encoding: base64} 를 함께 쓴다.
 *
 * @param path       저장소 루트 기준 상대 경로
 * @param content    파일 전체 내용. {@code deleted} 면 {@code null}
 * @param deleted    이 경로를 지우는가
 * @param executable 실행 비트. 🔴 <b>기존 파일을 고칠 때 이 값이 틀리면 권한이 조용히 바뀐다</b> —
 *                   {@code gradlew} 를 고치면서 {@code false} 로 보내면 실행 권한이 사라지고,
 *                   증상은 대상 저장소 CI 에서야 나타난다
 */
public record FileChange(String path, String content, boolean deleted, boolean executable) {

    /** git tree 항목의 mode. */
    public static final String MODE_FILE = "100644";
    public static final String MODE_EXECUTABLE = "100755";

    public FileChange {
        path = requirePath(path);
        if (deleted) {
            if (content != null) {
                throw new IllegalArgumentException("삭제되는 파일에 내용을 실을 수 없습니다: " + path);
            }
        } else if (content == null) {
            throw new IllegalArgumentException("파일 내용이 없습니다: " + path);
        }
    }

    public static FileChange modified(String path, String content) {
        return new FileChange(path, content, false, false);
    }

    public static FileChange deleted(String path) {
        return new FileChange(path, null, true, false);
    }

    public String mode() {
        return executable ? MODE_EXECUTABLE : MODE_FILE;
    }

    public int byteLength() {
        return content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * 🔴 경로가 그대로 git tree 항목이 된다. 정규화되지 않은 값을 넣으면 우리가 의도하지 않은
     * 파일을 덮어쓴다 — {@code ../} 는 저장소 밖을, 절대 경로는 루트를 가리킨다.
     */
    private static String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("파일 경로가 비어 있습니다");
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("/")) {
            throw new IllegalArgumentException("파일 경로는 저장소 루트 기준 상대 경로여야 합니다: " + trimmed);
        }
        if (trimmed.startsWith(".git/") || trimmed.equals(".git")) {
            // git 메타데이터를 tree 에 넣는 것은 정상 변경이 아니다
            throw new IllegalArgumentException("git 메타데이터 경로는 변경할 수 없습니다: " + trimmed);
        }
        for (String segment : trimmed.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("파일 경로에 빈 세그먼트나 상대 참조가 있습니다: " + trimmed);
            }
        }
        return trimmed;
    }

    /** 🔴 내용을 노출하지 않는다 — 로그·예외 메시지에 본문이 섞이지 않게 (S-4). */
    @Override
    public String toString() {
        return "FileChange[path=%s, deleted=%s, bytes=%d]".formatted(path, deleted, byteLength());
    }
}
