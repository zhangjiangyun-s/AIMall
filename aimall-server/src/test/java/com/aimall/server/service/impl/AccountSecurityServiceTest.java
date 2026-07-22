package com.aimall.server.service.impl;

import com.aimall.server.mapper.UmsMemberDeviceMapper;
import com.aimall.server.mapper.UmsMemberLoginHistoryMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountSecurityServiceTest {
    @Test
    void loginIsLimitedAcrossAccountIpAndDeviceDimensions() {
        UmsMemberLoginHistoryMapper history = mock(UmsMemberLoginHistoryMapper.class);
        when(history.selectCount(any())).thenReturn(0L, 12L, 0L, 0L);
        AccountSecurityService service = new AccountSecurityService(
                mock(UmsMemberMapper.class), history, mock(UmsMemberDeviceMapper.class)
        );

        assertThrows(RuntimeException.class,
                () -> service.checkLoginAllowed("alice", "203.0.113.10", "browser-a"));
    }

    @Test
    void passwordResetHasIndependentAccountIpAndDeviceLimits() {
        UmsMemberLoginHistoryMapper history = mock(UmsMemberLoginHistoryMapper.class);
        when(history.selectCount(any())).thenReturn(5L, 0L, 0L);
        AccountSecurityService service = new AccountSecurityService(
                mock(UmsMemberMapper.class), history, mock(UmsMemberDeviceMapper.class)
        );

        assertThrows(RuntimeException.class,
                () -> service.checkPasswordResetAllowed("alice@example.com", "203.0.113.10", "browser-a"));
    }
}
