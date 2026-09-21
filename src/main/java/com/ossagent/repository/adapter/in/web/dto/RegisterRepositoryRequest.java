package com.ossagent.repository.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterRepositoryRequest(
        @NotBlank String owner,
        @NotBlank String name,
        @NotBlank String url) {
}
