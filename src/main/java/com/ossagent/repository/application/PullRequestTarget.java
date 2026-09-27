package com.ossagent.repository.application;

import com.ossagent.repository.domain.RepositoryCoordinates;

/**
 * Draft PR 을 열기 위해 <b>대상 저장소에서 읽어야 하는 것 전부</b> — #23.
 *
 * <p>{@code ScanTarget} 과 같은 자리의 값이다. 트랜잭션 밖으로 들고 나가며 엔티티를
 * LAZY 인 채로 끌고 다니지 않는다.
 *
 * <h2>🔴 {@code template} 이 {@code null} 인 것은 「없다」다 — 「못 읽었다」가 아니다</h2>
 *
 * <p>못 읽은 경우는 이 값이 <b>만들어지지 않는다.</b> {@code fetchFile} 이 예외로 끊고,
 * 그 예외는 삼켜지지 않는다 — S-5. 「읽지 못했다」가 「규약이 없다」로 번역되는 순간
 * 대상 저장소의 양식을 무시한 PR 이 나간다.
 *
 * <p>⚠️ 그래서 <b>{@code Optional} 을 필드로 쓰지 않는다.</b> {@code Optional.empty()} 가
 * 두 가지를 뜻하게 되는 순간 이 구분이 흐려진다 — {@code RepositorySource.fetchFile} 이
 * 같은 이유로 계약을 좁혀 뒀다.
 *
 * @param upstream      원본 좌표. <b>읽기로 얻었고, 쓰기는 PR 생성 하나뿐이다</b>
 * @param defaultBranch PR 의 base 가 될 기준 브랜치
 * @param template      PR 템플릿 원문. <b>없으면 {@code null}</b>
 */
public record PullRequestTarget(RepositoryCoordinates upstream, String defaultBranch,
                                String template) {

    public PullRequestTarget {
        if (upstream == null) {
            throw new IllegalArgumentException("원본 좌표는 필수다");
        }
        if (defaultBranch == null || defaultBranch.isBlank()) {
            // 기준 브랜치를 모르면 PR 의 base 를 정할 수 없다. 추측으로 main 을 넣지 않는다 —
            // 틀린 base 위에 열린 PR 은 diff 가 통째로 어긋나 보인다
            throw new IllegalArgumentException(
                    "기준 브랜치를 알 수 없다 repo=" + upstream.fullName());
        }
        defaultBranch = defaultBranch.trim();
    }

    public boolean hasTemplate() {
        return template != null && !template.isBlank();
    }

    /** 🔴 템플릿 원문을 노출하지 않는다 (S-4 — 대상 저장소가 시크릿을 커밋해 뒀을 수 있다). */
    @Override
    public String toString() {
        return "PullRequestTarget[upstream=%s, base=%s, template=%s]"
                .formatted(upstream.fullName(), defaultBranch,
                        hasTemplate() ? template.length() + "자" : "없음");
    }
}
