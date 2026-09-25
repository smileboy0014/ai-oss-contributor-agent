package com.ossagent.repository.domain;

import java.util.List;

/**
 * 규약 판정에 쓰는 후보 경로와 그 <b>역할</b>.
 *
 * <h2>왜 역할을 가르나</h2>
 *
 * <p>Q-8 이 확정한 8경로는 <b>「AI 기여 금지 판정」을 위한 최소 경로</b>이지 수집 대상 전체가
 * 아니다. 이슈 #7 의 「수집 대상」과 S-5 의 표는 <b>README·PR 템플릿</b>도 요구한다.
 *
 * <p>그런데 둘을 같은 엄격도로 다루면 <b>AI 판정과 무관한 README 의 5xx 하나가 저장소를
 * 통째로 보류</b>시킨다. 보류는 사람만 풀 수 있고 <b>그 API(#24)는 아직 없다.</b>
 * Phase 1 대상이 한 곳이므로 복구 수단 없이 파이프라인이 멈춘다.
 *
 * <p>그래서 「못 읽으면 보류」는 {@link Role#REQUIRED} 에만 건다. 이것은 Q-8 이탈이 아니라
 * Q-8 이 전제한 「후보 경로」의 정의를 명시하는 것이다.
 *
 * <h2>🔴 확장자 변종을 빠뜨리면 S-5 가 무너진다</h2>
 *
 * <p>Q-8 이 대상 저장소 7곳을 실측해 확인했다 — <b>spring-boot·spring-data-redis 는
 * {@code CONTRIBUTING.adoc}</b> 이다. {@code .md} 만 찾으면 404 를 받고
 * <b>「규약이 있는데 못 읽은 것」을 「규약 없음 → 허용」으로 번역</b>한다.
 *
 * <p>그리고 <b>한 파일만 읽고 끝내지 않는다</b> — spring-kafka 의 {@code AGENTS.md} 는 한 줄이고
 * 「{@code CONTRIBUTING.md} 를 보라」가 전부다.
 *
 * @param path 저장소 기준 경로
 * @param role 판정 필수인가 부가 수집인가
 */
public record PolicyDocumentPath(String path, Role role) {

    public enum Role {
        /** 못 읽으면 🔴 <b>전체 보류</b> — AI 기여 허용 여부 판정에 필요하다 */
        REQUIRED,
        /** 못 읽어도 보류하지 않는다 — 해당 필드만 미수집 */
        SUPPLEMENTARY
    }

    public PolicyDocumentPath {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("경로가 비어 있습니다");
        }
        if (role == null) {
            throw new IllegalArgumentException("역할이 없습니다");
        }
    }

    public boolean isRequired() {
        return role == Role.REQUIRED;
    }

    /** AI 기여 허용 여부 판정에 필요한 경로 — Q-8 확정 목록 그대로. */
    public static final List<PolicyDocumentPath> REQUIRED_PATHS = List.of(
            new PolicyDocumentPath("AGENTS.md", Role.REQUIRED),
            new PolicyDocumentPath("CLAUDE.md", Role.REQUIRED),
            new PolicyDocumentPath("CONTRIBUTING.md", Role.REQUIRED),
            // 🔴 spring-boot · spring-data-redis 가 이것이다. 빼면 404 를 「규약 없음」으로 읽는다
            new PolicyDocumentPath("CONTRIBUTING.adoc", Role.REQUIRED),
            new PolicyDocumentPath("CONTRIBUTING.rst", Role.REQUIRED),
            new PolicyDocumentPath("CONTRIBUTING", Role.REQUIRED),
            new PolicyDocumentPath(".github/CONTRIBUTING.md", Role.REQUIRED),
            new PolicyDocumentPath(".github/CONTRIBUTING.adoc", Role.REQUIRED));

    /**
     * 규약 정보를 보태는 경로. 이슈 「수집 대상」과 S-5 표가 요구한다.
     *
     * <p>못 읽어도 보류하지 않는다 — AI 허용 여부 판정에 필요하지 않기 때문이다.
     */
    public static final List<PolicyDocumentPath> SUPPLEMENTARY_PATHS = List.of(
            new PolicyDocumentPath(".github/PULL_REQUEST_TEMPLATE.md", Role.SUPPLEMENTARY),
            new PolicyDocumentPath(".github/pull_request_template.md", Role.SUPPLEMENTARY),
            new PolicyDocumentPath("docs/PULL_REQUEST_TEMPLATE.md", Role.SUPPLEMENTARY),
            new PolicyDocumentPath("README.md", Role.SUPPLEMENTARY),
            new PolicyDocumentPath("README.adoc", Role.SUPPLEMENTARY));

    /** 수집할 경로 전체. */
    public static List<PolicyDocumentPath> all() {
        return java.util.stream.Stream
                .concat(REQUIRED_PATHS.stream(), SUPPLEMENTARY_PATHS.stream())
                .toList();
    }
}
