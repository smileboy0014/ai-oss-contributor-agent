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
 * @param content     {@link DocumentFetchOutcome#READ} 일 때만 채워진다. 그 외에는 {@code null}
 * @param reason      {@link DocumentFetchOutcome#UNREADABLE} 일 때만 채워진다. <b>우리 어휘</b>다
 * @param fingerprint 이 관측의 지문 — 이슈 #68. {@code null} 이면 <b>지문을 구하지 못했다</b>
 */
public record FetchedDocument(
        PolicyDocumentPath path,
        DocumentFetchOutcome outcome,
        @ExternalText(ExternalText.Source.TARGET_REPOSITORY) String content,
        UnreadableReason reason,
        DocumentFingerprint fingerprint) {

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
        // 🔴 내용을 받았는데 지문이 없을 수는 없다. 비면 「바뀐 것을 관측했다」를 영영
        //    말할 수 없게 되고, #68 이 닫으려는 구멍이 그대로 남는다
        if (outcome == DocumentFetchOutcome.READ && fingerprint == null) {
            throw new IllegalArgumentException("READ 인데 지문이 없습니다: " + path.path());
        }
        // 🔴 「없다」도 안정적인 관측이다. 여기를 null 로 두면 파일이 새로 생긴 것을
        //    탐지하지 못한다 — absent → 해시 변화가 바로 「규약이 생겼다」는 증거다
        if (outcome == DocumentFetchOutcome.ABSENT
                && !DocumentFingerprint.ABSENT.equals(fingerprint)) {
            throw new IllegalArgumentException("ABSENT 의 지문은 absent 여야 합니다: " + path.path());
        }
        // UNREADABLE 은 있어도 되고 없어도 된다 — 응답을 받았는지가 가른다(#68 FR-5)
    }

    public static FetchedDocument read(PolicyDocumentPath path, String content) {
        return new FetchedDocument(path, DocumentFetchOutcome.READ, content, null,
                DocumentFingerprint.of(content));
    }

    public static FetchedDocument absent(PolicyDocumentPath path) {
        return new FetchedDocument(path, DocumentFetchOutcome.ABSENT, null, null,
                DocumentFingerprint.ABSENT);
    }

    /**
     * 못 읽었고 <b>지문도 구하지 못했다</b> — 응답 자체를 받지 못한 경우(5xx · 레이트리밋).
     *
     * <p>🔴 이 경우 규약이 바뀌었는지 <b>알 방법이 없다.</b> 「바뀌지 않았다」로 읽히지 않게
     * {@code null} 을 그대로 둔다 — {@code absent} 로 채우면 「파일이 사라졌다」가 된다.
     */
    public static FetchedDocument unreadable(PolicyDocumentPath path, UnreadableReason reason) {
        return new FetchedDocument(path, DocumentFetchOutcome.UNREADABLE, null, reason, null);
    }

    /**
     * 못 읽었지만 <b>지문은 있다</b> — 응답은 받았으나 판정에 쓸 수 없는 경우.
     *
     * <p>🔴 <b>이 조합이 #68 의 핵심이다.</b> 가장 현실적인 예가
     * {@link UnreadableReason#TRUNCATED} 다 — 내용을 받긴 했고 우리 상한을 넘어 판정에 쓰지
     * 않을 뿐이라, <b>바뀌었다는 사실은 알 수 있다.</b> 「판정이 섰는가」와 「바뀌었는가」가
     * 독립적인 물음인 이유가 여기 있다.
     */
    public static FetchedDocument unreadable(PolicyDocumentPath path, UnreadableReason reason,
            DocumentFingerprint fingerprint) {
        return new FetchedDocument(path, DocumentFetchOutcome.UNREADABLE, null, reason, fingerprint);
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
        return "FetchedDocument[path=%s, outcome=%s, size=%d, reason=%s, fingerprinted=%s]"
                .formatted(path.path(), outcome, content == null ? 0 : content.length(), reason,
                        fingerprint != null);
    }
}
