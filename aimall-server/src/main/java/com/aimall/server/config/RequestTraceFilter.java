package com.aimall.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requested = request.getHeader("X-Trace-Id");
        String clientTraceId = requested != null && requested.matches("[A-Za-z0-9_-]{8,64}")
                ? requested : null;
        String traceId = UUID.randomUUID().toString();
        response.setHeader("X-Trace-Id", traceId);
        MDC.put("traceId", traceId);
        if (clientTraceId != null) MDC.put("clientTraceId", clientTraceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("clientTraceId");
        }
    }
}
