package com.aimall.server.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {
    private final ClientIpResolver resolver = new ClientIpResolver("172.16.0.0/12");

    @Test
    void ignoresForwardedHeadersFromUntrustedDirectClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        assertEquals("198.51.100.20", resolver.resolve(request));
    }

    @Test
    void walksTrustedProxyChainFromRightToLeft() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.3");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 172.18.0.2");

        assertEquals("203.0.113.7", resolver.resolve(request));
    }
}
