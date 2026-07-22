package com.aimall.server.config;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class SaTokenConfigureTest {

    @Test
    void protectedRouteInterceptorActuallyChecksLogin() throws Exception {
        SaTokenConfigure configure = new SaTokenConfigure(
                mock(AdminAuthInterceptor.class),
                mock(InternalServiceAuthInterceptor.class),
                mock(AdminOperationAuditInterceptor.class)
        );

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            boolean allowed = configure.loginRequiredInterceptor().preHandle(
                    new MockHttpServletRequest("POST", "/api/ai/chat"),
                    new MockHttpServletResponse(),
                    new Object()
            );

            assertTrue(allowed);
            stpUtil.verify(StpUtil::checkLogin);
        }
    }
}
