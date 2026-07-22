package com.aimall.server.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class BusinessException extends RuntimeException {
    private final String errorCode;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public BusinessException(String errorCode, String message, HttpStatus status) {
        this(errorCode, message, status, Map.of(), null);
    }

    public BusinessException(
            String errorCode,
            String message,
            HttpStatus status,
            Map<String, Object> details,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
        this.status = status;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
