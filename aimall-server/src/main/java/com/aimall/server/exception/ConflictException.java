package com.aimall.server.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends BusinessException {
    public ConflictException(String errorCode, String message) {
        super(errorCode, message, HttpStatus.CONFLICT);
    }

    public ConflictException(String message) {
        this("BUSINESS_CONFLICT", message);
    }
}
