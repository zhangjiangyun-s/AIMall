package com.aimall.server.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ExternalDependencyException extends BusinessException {
    public ExternalDependencyException(String dependency, String message, Throwable cause) {
        super(
                "EXTERNAL_DEPENDENCY_UNAVAILABLE",
                message,
                HttpStatus.SERVICE_UNAVAILABLE,
                Map.of("dependency", dependency),
                cause
        );
    }
}
