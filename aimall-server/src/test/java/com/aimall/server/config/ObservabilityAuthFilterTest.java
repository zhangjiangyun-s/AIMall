package com.aimall.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservabilityAuthFilterTest {
    @Test
    void productionPrometheusEndpointRejectsMissingToken() throws Exception {
        ObservabilityAuthFilter filter = new ObservabilityAuthFilter("o".repeat(40), "prod");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void bearerTokenAllowsPrometheusScrape() throws Exception {
        String token = "o".repeat(40);
        ObservabilityAuthFilter filter = new ObservabilityAuthFilter(token, "prod");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }
}
