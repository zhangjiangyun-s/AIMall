package com.aimall.server.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super(
                "RESOURCE_NOT_FOUND",
                "资源不存在",
                HttpStatus.NOT_FOUND,
                Map.of("resourceType", resourceType, "resourceId", String.valueOf(resourceId)),
                null
        );
    }

    public ResourceNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }
}
