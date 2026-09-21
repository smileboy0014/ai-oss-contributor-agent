package com.ossagent.repository.domain;

public class RepositoryAlreadyRegisteredException extends RuntimeException {

    public RepositoryAlreadyRegisteredException(String url) {
        super("Repository is already registered: " + url);
    }
}
