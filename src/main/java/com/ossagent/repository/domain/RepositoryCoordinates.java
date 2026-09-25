package com.ossagent.repository.domain;

import java.util.regex.Pattern;

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

    /**
     * GitHub 이 owner·repo 이름에 허용하는 문자.
     *
     * <p>🔴 <b>검증이 값 타입의 책임인 이유</b> — 좌표는 사용자가 등록한 대상 저장소에서 오는
     * <b>외부 입력</b>이고, 어댑터가 이것을 {@code "/repos/%s/%s"} 로 조립해 요청 경로에 넣는다.
     * {@code ?}·{@code #} 가 섞이면 쿼리·프래그먼트 경계가 밀리고 {@code ..} 가 섞이면 경로가
     * 다르게 정규화되어, 「우리가 어느 URL 을 부르는지 통제하고 있다」는 전제가 깨진다.
     * 그 전제 위에 S-1(읽기 전용 표면)과 예외·로그의 {@code path=} 가 서 있다.
     */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]+");

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
        if (!ALLOWED.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "저장소 좌표의 %s 에 허용되지 않는 문자가 있습니다(A-Za-z0-9 . _ - 만 가능): %s"
                            .formatted(field, trimmed));
        }
        if (trimmed.equals(".") || trimmed.equals("..")) {
            throw new IllegalArgumentException(
                    "저장소 좌표의 " + field + " 가 경로 세그먼트일 수 없습니다: " + trimmed);
        }
        return trimmed;
    }
}
