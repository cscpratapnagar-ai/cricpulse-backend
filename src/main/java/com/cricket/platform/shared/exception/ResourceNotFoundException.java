package com.cricket.platform.shared.exception;

public final class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resource, Object id) {
        super("RESOURCE_NOT_FOUND", resource + " was not found: " + id);
    }
}
