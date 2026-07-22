package com.aimall.server.service.impl;

import com.aimall.server.mapper.UmsEmailVerificationCodeMapper;
import com.aimall.server.mapper.UmsEmailVerificationSendLogMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import com.aimall.server.service.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "aimall.mailhog.integration", matches = "true")
class EmailVerificationMailhogIntegrationTest {
    @Test
    void sendsTheRealRegistrationTemplateToMailhog() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:8025/api/v1/messages")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding()
        );
        UmsEmailVerificationCodeMapper codes = mock(UmsEmailVerificationCodeMapper.class);
        when(codes.upsertActive(anyString(), anyString(), anyString(), anyInt(), any(), any(), any()))
                .thenReturn(1);
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(1025);
        EmailVerificationServiceImpl service = new EmailVerificationServiceImpl(
                codes,
                mock(UmsEmailVerificationSendLogMapper.class),
                mock(UmsMemberMapper.class),
                mailSender,
                true,
                "AIMall <no-reply@aimall.local>",
                "m".repeat(40),
                10,
                60,
                5,
                () -> 123456
        );

        service.sendCode(
                "mailhog-test@example.com", EmailVerificationService.Purpose.REGISTER, "127.0.0.1"
        );

        String messages = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:8025/api/v2/messages")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        ).body();
        assertTrue(messages.contains("mailhog-test@example.com"));
        assertTrue(messages.contains("123456"));
        assertTrue(messages.contains("AIMall"));
    }
}
