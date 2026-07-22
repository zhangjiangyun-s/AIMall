package com.aimall.server.service.impl;

import com.aimall.server.entity.UmsMember;
import com.aimall.server.mapper.UmsMemberMapper;
import com.aimall.server.service.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceImplTest {
    @Test
    void registrationConsumesEmailCodeAndPersistsNormalizedEmail() {
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        AccountSecurityService security = mock(AccountSecurityService.class);
        EmailVerificationService verification = mock(EmailVerificationService.class);
        when(verification.normalize(" User@Example.com ")).thenReturn("user@example.com");
        when(members.selectCount(any())).thenReturn(0L);
        UserServiceImpl service = new UserServiceImpl(members, security, verification);

        service.register(
                "member01", "StrongPassword#1", "Member",
                " User@Example.com ", "123456"
        );

        verify(security).validatePassword("StrongPassword#1");
        verify(verification).consume(
                "user@example.com", EmailVerificationService.Purpose.REGISTER, "123456"
        );
        ArgumentCaptor<UmsMember> member = ArgumentCaptor.forClass(UmsMember.class);
        verify(members).insert(member.capture());
        assertEquals("user@example.com", member.getValue().getEmail());
    }
}
