package com.ossagent.repository.domain;

/**
 * 대상 저장소에서 읽어 온 파일 한 개. 내용은 <b>디코드가 끝난</b> 텍스트다.
 *
 * <p>{@code CONTRIBUTING.md} · {@code AGENTS.md} · 빌드 설정처럼 기여 규약을 판정할 파일이
 * 이 타입으로 들어온다(이슈 #7). base64 디코딩은 어댑터에서 끝낸다 — 도메인이 GitHub 의
 * 인코딩 방식을 알 이유가 없다.
 *
 * <p>🔴 <b>{@code content} 를 로그에 찍지 않는다.</b> 대상 저장소가 시크릿을 커밋해 뒀을 수 있다 —
 * {@code .claude/rules/conventions/logging.md}. 크기만 남긴다.
 *
 * @param path    저장소 기준 경로
 * @param content 디코드된 내용
 */
public record RepositoryFile(String path, String content) {

    public RepositoryFile {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("파일 경로가 비어 있습니다");
        }
        content = content == null ? "" : content;
    }

    public int size() {
        return content.length();
    }

    public boolean isEmpty() {
        return content.isBlank();
    }

    /** 🔴 내용을 포함하지 않는다. 이 타입이 실수로 로그에 실려도 파일 본문이 나가지 않게 한다. */
    @Override
    public String toString() {
        return "RepositoryFile[path=%s, size=%d]".formatted(path, size());
    }
}
