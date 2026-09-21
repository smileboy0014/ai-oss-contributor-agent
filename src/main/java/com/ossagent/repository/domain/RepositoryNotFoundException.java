package com.ossagent.repository.domain;

public class RepositoryNotFoundException extends RuntimeException {

    public RepositoryNotFoundException(Long id) {
        super("Repository not found: " + id);
    }
}
