package com.ossagent.repository.domain;

import java.util.List;

/**
 * 규약 문서 수집 결과 전체. <b>판정의 입력</b>이다.
 *
 * <p>여기서 「보류인가 / 허용인가 / LLM 을 불러야 하는가」가 갈린다 — Q-8 확정.
 * 그 판정을 UseCase 의 {@code if} 더미가 아니라 이 타입의 메서드로 두는 이유는,
 * <b>같은 질문을 두 곳에서 다르게 답하는 일</b>을 막기 위해서다.
 */
public record RepositoryDocuments(List<FetchedDocument> documents) {

    public RepositoryDocuments {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    /**
     * 🔴 <b>판정 필수 경로 중 영구적으로 못 읽은 것이 있는가</b> — 있으면 보류다.
     *
     * <p>{@link PolicyDocumentPath.Role#SUPPLEMENTARY} 는 세지 않는다. README 의 5xx 하나가
     * 저장소를 통째로 보류시키면, 보류를 풀 수단(#24)이 없는 지금 파이프라인이 멈춘다.
     */
    public boolean hasPermanentlyUnreadableRequired() {
        return documents.stream()
                .filter(d -> d.path().isRequired())
                .anyMatch(d -> d.isUnreadable() && !d.isTransientFailure());
    }

    /**
     * 판정 필수 경로 중 <b>일시적</b> 실패가 있는가 — 있으면 <b>아무것도 쓰지 않고 중단</b>한다.
     *
     * <p>다음 스캔에서 자연히 재시도된다. 정책이 없으므로 구현 단계는 어차피 막히고(FR-4),
     * 안전 성질은 보류와 같으면서 복구만 자동이다.
     */
    public boolean hasTransientlyUnreadableRequired() {
        return documents.stream()
                .filter(d -> d.path().isRequired())
                .anyMatch(d -> d.isUnreadable() && d.isTransientFailure());
    }

    /**
     * 판정 필수 경로를 하나도 읽지 못했고, 못 읽은 이유가 <b>전부 404</b> 인가.
     *
     * <p>이때는 <b>허용</b>이다 — 문서가 없으면 금지 표기가 존재할 수 없다(Q-8 확정 ①).
     * LLM 을 부르지 않는다. 판정할 텍스트가 없는데 토큰을 쓸 이유가 없다.
     */
    public boolean allRequiredAbsent() {
        List<FetchedDocument> required = documents.stream()
                .filter(d -> d.path().isRequired())
                .toList();
        return !required.isEmpty()
                && required.stream().allMatch(d -> d.outcome() == DocumentFetchOutcome.ABSENT);
    }

    /**
     * 판정에 넣을 문서들 — 읽은 것만.
     *
     * <p>일부는 READ 이고 일부는 ABSENT 인 <b>혼합</b>이 실전에서 가장 흔하다
     * (Q-8 실측: spring-framework 는 {@code AGENTS.md} 404 + {@code CONTRIBUTING.md} 존재).
     * 이때는 <b>읽은 것으로 판정</b>한다.
     */
    public List<FetchedDocument> readDocuments() {
        return documents.stream().filter(FetchedDocument::isRead).toList();
    }

    /**
     * 보류 사유를 사람이 읽을 형태로. <b>우리 어휘만</b> 들어간다 — S-4.
     *
     * <p><b>필수 경로만</b> 담는다. 보류를 만드는 근거가 필수 경로뿐인데 부가 경로(README 등)
     * 실패까지 섞으면, #24 가 「레이트리밋으로 보류된 것만 재시도」를 고를 때 노이즈가 된다.
     */
    public String requiredPendingReason() {
        return documents.stream()
                .filter(d -> d.path().isRequired())
                .filter(FetchedDocument::isUnreadable)
                .map(d -> d.path().path() + "=" + d.reason())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    /** 🔴 문서 내용을 포함하지 않는다. */
    @Override
    public String toString() {
        return "RepositoryDocuments[count=%d, read=%d]"
                .formatted(documents.size(), readDocuments().size());
    }
}
