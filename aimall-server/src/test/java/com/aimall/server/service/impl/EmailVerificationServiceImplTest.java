package com.aimall.server.service.impl;

import com.aimall.server.entity.UmsEmailVerificationCode;
import com.aimall.server.mapper.UmsEmailVerificationCodeMapper;
import com.aimall.server.mapper.UmsEmailVerificationSendLogMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import com.aimall.server.service.EmailVerificationService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailVerificationServiceImplTest {
    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "email-verification-test"),
                UmsEmailVerificationCode.class
        );
    }

    @Test
    void productionConstructorIsExplicitlySelectedForSpringInjection() {
        Constructor<?> production = java.util.Arrays.stream(EmailVerificationServiceImpl.class.getConstructors())
                .findFirst()
                .orElseThrow();

        assertTrue(production.isAnnotationPresent(Autowired.class));
    }

    @Test
    void sendsHtmlMailStoresOnlyHashAndConsumesCodeOnce() throws Exception {
        UmsEmailVerificationCodeMapper codes = mock(UmsEmailVerificationCodeMapper.class);
        UmsEmailVerificationSendLogMapper logs = mock(UmsEmailVerificationSendLogMapper.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);
        when(codes.upsertActive(anyString(), anyString(), anyString(), anyInt(), any(), any(), any()))
                .thenReturn(1);
        when(codes.update(isNull(), any())).thenReturn(1);
        EmailVerificationServiceImpl service = service(codes, logs, members, mailSender);

        service.sendCode(" User@Example.com ", EmailVerificationService.Purpose.REGISTER, "127.0.0.1");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(codes).upsertActive(
                eq("user@example.com"), eq("REGISTER"), hash.capture(), eq(5), any(), any(), any()
        );
        assertTrue(hash.getValue().matches("[0-9a-f]{64}"));
        verify(mailSender).send(message);

        String deliveredCode = "123456";
        assertFalse(hash.getValue().contains(deliveredCode));

        UmsEmailVerificationCode challenge = new UmsEmailVerificationCode();
        challenge.setId(10L);
        challenge.setCodeHash(hash.getValue());
        challenge.setStatus("ACTIVE");
        challenge.setFailedAttempts(0);
        challenge.setMaxAttempts(5);
        challenge.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(codes.selectActiveForUpdate("user@example.com", "REGISTER")).thenReturn(challenge);

        assertDoesNotThrow(() -> service.consume(
                "user@example.com", EmailVerificationService.Purpose.REGISTER, deliveredCode
        ));
        verify(codes).update(isNull(), any());
    }

    @Test
    void cooldownPreventsASecondDelivery() {
        UmsEmailVerificationCodeMapper codes = mock(UmsEmailVerificationCodeMapper.class);
        UmsEmailVerificationSendLogMapper logs = mock(UmsEmailVerificationSendLogMapper.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(codes.upsertActive(anyString(), anyString(), anyString(), anyInt(), any(), any(), any()))
                .thenReturn(0);
        EmailVerificationServiceImpl service = service(codes, logs, members, mailSender);

        assertThrows(IllegalStateException.class, () -> service.sendCode(
                "user@example.com", EmailVerificationService.Purpose.REGISTER, "127.0.0.1"
        ));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    private EmailVerificationServiceImpl service(
            UmsEmailVerificationCodeMapper codes,
            UmsEmailVerificationSendLogMapper logs,
            UmsMemberMapper members,
            JavaMailSender mailSender
    ) {
        return new EmailVerificationServiceImpl(
                codes, logs, members, mailSender, true,
                "AIMall <no-reply@aimall.local>", "a".repeat(40), 10, 60, 5, () -> 123456
        );
    }
}
