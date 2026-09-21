package com.ossagent.repository.adapter.in.web.dto;

import com.ossagent.repository.domain.OssRepository;
import java.time.Instant;

public record RepositoryResponse(
        Long id,
        String owner,
        String name,
        String url,
        boolean enabled,
        Instant lastScannedAt) {

    public static RepositoryResponse from(OssRepository repository) {
        return new RepositoryResponse(
                repository.getId(),
                repository.getOwner(),
                repository.getName(),
                repository.getUrl(),
                repository.isEnabled(),
                repository.getLastScannedAt());
    }
}
