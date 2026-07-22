package com.aimall.server.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestTraceFilterTest {
    @Test
    void clientTraceNeverBecomesAuthoritativeServerTrace() throws Exception {
        RequestTraceFilter filter = new RequestTraceFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader("X-Trace-Id", "client-trace-1234");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] observed = new String[2];
        FilterChain chain = (req, res) -> {
            observed[0] = MDC.get("traceId");
            observed[1] = MDC.get("clientTraceId");
        };

        filter.doFilter(request, response, chain);

        assertNotEquals("client-trace-1234", observed[0]);
        assertEquals("client-trace-1234", observed[1]);
        assertEquals(observed[0], response.getHeader("X-Trace-Id"));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("clientTraceId"));
    }
}
