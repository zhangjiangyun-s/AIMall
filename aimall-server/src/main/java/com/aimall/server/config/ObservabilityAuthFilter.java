package com.aimall.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ObservabilityAuthFilter extends OncePerRequestFilter {
    private final String token;
    private final boolean production;

    public ObservabilityAuthFilter(
            @Value("${aimall.observability.token:}") String token,
            @Value("${aimall.environment:local}") String environment
    ) {
        this.token = token == null ? "" : token.trim();
        this.production = "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/actuator/prometheus".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!production && token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        String supplied = request.getHeader("X-Observability-Token");
        String authorization = request.getHeader("Authorization");
        if ((supplied == null || supplied.isBlank()) && authorization != null && authorization.startsWith("Bearer ")) {
            supplied = authorization.substring(7).trim();
        }
        if (!constantTimeEquals(token, supplied)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":1,\"errorCode\":\"OBSERVABILITY_UNAUTHORIZED\",\"message\":\"unauthorized\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        if (expected.isBlank() || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8)
        );
    }
}
