package com.ossagent.repository.domain;

/**
 * 대상 저장소를 가리키는 좌표 — {@code owner/name}.
 *
 * <p>이 값 타입은 {@code repository} 도메인이 소유하고, {@code issue} 등 다른 도메인이 <b>import 한다.</b>
 * 규율 ④ 가 금지하는 것은 남의 <b>엔티티·Spring Data 인터페이스</b> 직접 import 이고
 * 값 타입은 허용된다 — {@code .claude/rules/conventions/architecture.md}.
 * {@code owner}·{@code name} 문자열 두 개를 도메인마다 따로 들고 다니면
 * {@code fullName()} 조립 규칙이 세 곳에 복제된다.
 *
 * <p>여기서 말하는 저장소는 <b>대상 저장소</b>(외부 OSS)다. 이 프로젝트 자신이 아니다.
 */
public record RepositoryCoordinates(String owner, String name) {

    public RepositoryCoordinates {
        owner = require(owner, "owner");
        name = require(name, "name");
    }

    /** {@code "spring-projects/spring-kafka"} 형태의 문자열에서 만든다. */
    public static RepositoryCoordinates parse(String fullName) {
        if (fullName == null) {
            throw new IllegalArgumentException("저장소 좌표가 비어 있습니다");
        }
        String[] parts = fullName.trim().split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("저장소 좌표는 'owner/name' 형식이어야 합니다: " + fullName);
        }
        return new RepositoryCoordinates(parts[0], parts[1]);
    }

    /** GitHub 이 쓰는 표기. 로그의 저장소 식별자로도 이것을 쓴다. */
    public String fullName() {
        return owner + "/" + name;
    }

    @Override
    public String toString() {
        return fullName();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("저장소 좌표의 " + field + " 가 비어 있습니다");
        }
        String trimmed = value.trim();
        if (trimmed.contains("/")) {
            throw new IllegalArgumentException(
                    "저장소 좌표의 " + field + " 에 '/' 가 들어갈 수 없습니다: " + value);
        }
        return trimmed;
    }
}
