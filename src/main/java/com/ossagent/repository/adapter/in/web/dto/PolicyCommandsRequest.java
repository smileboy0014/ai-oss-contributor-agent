package com.ossagent.repository.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 빌드·테스트 명령 수동 설정 — #102.
 *
 * <p>길이 상한은 컬럼({@code VARCHAR(255)})과 같다. 화이트리스트 검사(쉘 메타문자·런처)는
 * UseCase 가 검증기와 같은 파서로 한다.
 */
public record PolicyCommandsRequest(
        @Size(max = 255) String javaVersion,
        @NotBlank @Size(max = 255) String buildCommand,
        @Size(max = 255) String testCommand) {
}
