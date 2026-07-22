package com.aimall.server.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final InternalServiceAuthInterceptor internalServiceAuthInterceptor;
    private final AdminOperationAuditInterceptor adminOperationAuditInterceptor;

    public SaTokenConfigure(
            AdminAuthInterceptor adminAuthInterceptor,
            InternalServiceAuthInterceptor internalServiceAuthInterceptor,
            AdminOperationAuditInterceptor adminOperationAuditInterceptor
    ) {
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.internalServiceAuthInterceptor = internalServiceAuthInterceptor;
        this.adminOperationAuditInterceptor = adminOperationAuditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminOperationAuditInterceptor)
                .addPathPatterns("/api/admin/**", "/api/ai/vector/**");
        registry.addInterceptor(loginRequiredInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/api/health",
                        "/api/health/liveness",
                        "/api/health/startup",
                        "/api/health/readiness/**",
                        "/api/products/**",
                        "/api/user/login",
                        "/api/user/register",
                        "/api/user/email/code",
                        "/api/user/password/reset",
                        "/api/admin/login",
                        "/api/pay/alipay/notify",
                        "/api/pay/alipay/return",
                        "/api/home/**",
                        "/actuator/prometheus",
                        "/internal/ai/**",
                        "/favicon.ico"
                );
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/admin/**", "/api/ai/vector/**", "/api/health/integration")
                .excludePathPatterns("/api/admin/login");
        registry.addInterceptor(internalServiceAuthInterceptor)
                .addPathPatterns("/internal/ai/**", "/api/health/startup", "/api/health/readiness/**");
    }

    SaInterceptor loginRequiredInterceptor() {
        return new SaInterceptor(handler -> StpUtil.checkLogin());
    }
}
