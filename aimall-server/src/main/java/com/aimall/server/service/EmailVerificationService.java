package com.aimall.server.service;

public interface EmailVerificationService {
    enum Purpose {
        REGISTER,
        PASSWORD_RESET
    }

    void sendCode(String email, Purpose purpose, String clientIp);

    void consume(String email, Purpose purpose, String code);

    String normalize(String email);
}
