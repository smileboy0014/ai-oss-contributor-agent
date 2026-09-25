package com.ossagent.repository.domain;

import com.ossagent.support.ExternalText;

/**
 * 규약 문서 하나를 읽으려 한 결과 한 건.
 *
 * <p>🔴 <b>{@code content} 를 로그에 찍지 않는다.</b> 대상 저장소가 시크릿을 커밋해 뒀을 수 있다.
 * {@link #toString()} 이 내용을 빼고, {@code RepositoryFile} 도 같은 처리를 한다.
 *
 * @param path    어느 경로를 보려 했나 (역할 포함)
 * @param outcome 읽었나 · 없었나 · 못 읽었나
 * @param content {@link DocumentFetchOutcome#READ} 일 때만 채워진다. 그 외에는 {@code null}
 * @param reason  {@link DocumentFetchOutcome#UNREADABLE} 일 때만 채워진다. <b>우리 어휘</b>다
 */
public record FetchedDocument(
        PolicyDocumentPath path,
        DocumentFetchOutcome outcome,
        @ExternalText(ExternalText.Source.TARGET_REPOSITORY) String content,
        UnreadableReason reason) {

    public FetchedDocument {
        if (path == null || outcome == null) {
            throw new IllegalArgumentException("경로와 결과는 필수입니다");
        }
        if (outcome == DocumentFetchOutcome.READ && content == null) {
            throw new IllegalArgumentException("READ 인데 내용이 없습니다: " + path.path());
        }
        if (outcome == DocumentFetchOutcome.UNREADABLE && reason == null) {
            // 사유 없는 UNREADABLE 은 #24 가 처리 대상을 고를 수 없게 만든다
            throw new IllegalArgumentException("UNREADABLE 인데 사유가 없습니다: " + path.path());
        }
        if (outcome != DocumentFetchOutcome.READ && content != null) {
            throw new IllegalArgumentException("READ 가 아닌데 내용이 있습니다: " + path.path());
        }
    }

    public static FetchedDocument read(PolicyDocumentPath path, String content) {
        return new FetchedDocument(path, DocumentFetchOutcome.READ, content, null);
    }

    public static FetchedDocument absent(PolicyDocumentPath path) {
        return new FetchedDocument(path, DocumentFetchOutcome.ABSENT, null, null);
    }

    public static FetchedDocument unreadable(PolicyDocumentPath path, UnreadableReason reason) {
        return new FetchedDocument(path, DocumentFetchOutcome.UNREADABLE, null, reason);
    }

    public boolean isRead() {
        return outcome == DocumentFetchOutcome.READ;
    }

    public boolean isUnreadable() {
        return outcome == DocumentFetchOutcome.UNREADABLE;
    }

    /**
     * 일시적 실패인가 — 재시도하면 달라질 수 있는가.
     *
     * <p>이 구분이 <b>보류 행을 만들지 말지</b>를 가른다. 일시적 실패로 보류를 만들면
     * 한 시간 뒤면 저절로 풀렸을 일이 <b>사람이 풀어야 하는 영구 보류</b>가 된다 —
     * 해소 API(#24)가 아직 없으므로 되돌릴 수단이 없다.
     */
    public boolean isTransientFailure() {
        return reason == UnreadableReason.RATE_LIMITED || reason == UnreadableReason.SERVER_ERROR;
    }

    /** 🔴 내용을 포함하지 않는다. 실수로 로그에 실려도 파일 본문이 나가지 않게 한다. */
    @Override
    public String toString() {
        return "FetchedDocument[path=%s, outcome=%s, size=%d, reason=%s]"
                .formatted(path.path(), outcome, content == null ? 0 : content.length(), reason);
    }
}
