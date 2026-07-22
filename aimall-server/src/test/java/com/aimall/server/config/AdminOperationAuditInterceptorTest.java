package com.aimall.server.config;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.entity.AdminOperationAudit;
import com.aimall.server.mapper.AdminOperationAuditMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;
import org.mockito.MockedStatic;

class AdminOperationAuditInterceptorTest {

    @Test
    void recordsForwardedClientIpAndResolvedAuthorizationFailure() {
        AdminOperationAuditMapper mapper = mock(AdminOperationAuditMapper.class);
        AdminOperationAuditInterceptor interceptor = new AdminOperationAuditInterceptor(
                mapper,
                new com.aimall.server.common.ClientIpResolver("172.16.0.0/12")
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/products");
        request.setRemoteAddr("172.18.0.3");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 172.18.0.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(403);
        interceptor.preHandle(request, response, new Object());

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(false);
            interceptor.afterCompletion(request, response, new Object(), null);
        }

        ArgumentCaptor<AdminOperationAudit> captor = ArgumentCaptor.forClass(AdminOperationAudit.class);
        verify(mapper).insert(captor.capture());
        assertEquals("203.0.113.7", captor.getValue().getClientIp());
        assertEquals(0, captor.getValue().getSuccess());
        assertEquals("HTTP 403", captor.getValue().getErrorMessage());
    }
}
