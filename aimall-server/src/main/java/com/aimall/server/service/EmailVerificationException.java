package com.aimall.server.service;

public class EmailVerificationException extends IllegalArgumentException {
    public EmailVerificationException(String message) {
        super(message);
    }
}
