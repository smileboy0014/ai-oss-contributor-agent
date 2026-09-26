package com.ossagent.repository.domain;

import java.util.Locale;

/**
 * 대상 저장소 파일 트리의 항목 하나 — <b>경로만 있고 내용은 없다.</b>
 *
 * <p>이슈 #15 의 선별은 두 단계다. 먼저 트리 한 번으로 <b>경로 전체</b>를 받아 점수를 매기고,
 * 상위 몇 개만 {@link RepositorySource#fetchFile} 로 <b>내용</b>을 읽는다. 이 타입이 1단계의
 * 단위다 — 수천 개가 만들어지므로 내용을 담지 않는 것이 설계다.
 *
 * @param path 저장소 루트 기준 경로. 디렉터리 구분자는 {@code /} 다
 * @param type {@link EntryType} — 내용을 읽을 수 있는 것은 {@link EntryType#BLOB} 뿐이다
 * @param size 바이트 크기. GitHub 은 blob 에만 준다. 모르면 {@code 0}
 */
public record RepositoryTreeEntry(String path, EntryType type, int size) {

    /**
     * 트리 항목의 종류.
     *
     * <p>🔴 {@link #OTHER} 를 두는 것이 중요하다. 서브모듈({@code commit})·심볼릭링크가
     * 여기 들어오는데, 이것을 {@link #BLOB} 으로 뭉뚱그리면 <b>읽을 수 없는 경로를 후보로
     * 올려 호출 예산만 태운다.</b> 모르는 종류는 읽지 않는다.
     */
    public enum EntryType {
        /** 파일 — 내용을 읽을 수 있다 */
        BLOB,
        /** 디렉터리 */
        TREE,
        /** 서브모듈·심볼릭링크 등. <b>읽지 않는다</b> */
        OTHER;

        /**
         * 심볼릭링크의 git 파일 모드.
         *
         * <p>🔴 <b>{@code type} 만 보면 놓친다.</b> 심볼릭링크는 트리 응답에서
         * {@code type: "blob"} 으로 오고 {@code mode} 로만 구분된다. blob 으로 받아들이면
         * 후보에 올라가고, Contents API 가 {@code type != "file"} 이라며 예외를 던진다 —
         * 예산을 태우고 예외 처리까지 타는 경로다.
         */
        private static final String SYMLINK_MODE = "120000";

        /**
         * GitHub 트리 응답의 {@code type}·{@code mode} 를 옮긴다. 모르는 값은 {@link #OTHER} 다.
         *
         * @param mode {@code null} 이면 모드 판정을 건너뛴다 — 그때는 {@code type} 만 본다
         */
        public static EntryType from(String rawType, String mode) {
            if (mode != null && SYMLINK_MODE.equals(mode.trim())) {
                return OTHER;
            }
            if (rawType == null) {
                return OTHER;
            }
            return switch (rawType.trim().toLowerCase(Locale.ROOT)) {
                case "blob" -> BLOB;
                case "tree" -> TREE;
                // "commit" = 서브모듈. 읽을 수 없다
                default -> OTHER;
            };
        }
    }

    public RepositoryTreeEntry {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("트리 항목의 경로가 비어 있습니다");
        }
        path = path.replace('\\', '/');
        type = type == null ? EntryType.OTHER : type;
        size = Math.max(size, 0);
    }

    public boolean isBlob() {
        return type == EntryType.BLOB;
    }

    /** 경로의 마지막 구간. {@code src/main/Foo.java} → {@code Foo.java} */
    public String fileName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * 확장자를 뗀 파일 이름. {@code Foo.java} → {@code Foo}
     *
     * <p>클래스 이름 매칭의 좌변이다 — 이슈가 말한 {@code KafkaMessageListenerContainer} 가
     * 이 값과 맞아떨어진다.
     */
    public String baseName() {
        String fileName = fileName();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 소문자 확장자. 없으면 빈 문자열 */
    public String extension() {
        String fileName = fileName();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 🔴 <b>경로만 남긴다.</b> 내용을 담지 않는 타입이라 그대로 찍어도 본문이 나갈 수 없지만,
     * 대상 저장소의 경로 자체가 임의 문자열이므로 로그 인젝션 경로이기는 하다 —
     * 포맷 문자열로 쓰지 않는다({@code logging.md}).
     */
    @Override
    public String toString() {
        return "RepositoryTreeEntry[path=%s, type=%s, size=%d]".formatted(path, type, size);
    }
}
