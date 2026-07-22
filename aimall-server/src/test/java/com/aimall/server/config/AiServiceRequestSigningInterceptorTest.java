package com.aimall.server.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceRequestSigningInterceptorTest {

    @Test
    void signsQueryAndBodyForJavaToAiRequest() throws Exception {
        HttpRequest request = mock(HttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        when(request.getURI()).thenReturn(URI.create("http://localhost:8000/ai/vector/sync?limit=10"));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        byte[] body = "{\"status\":\"PENDING\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        MDC.put("traceId", "stage9-trace-001");
        try {
            new AiServiceRequestSigningInterceptor("test-secret").intercept(request, body, execution);
        } finally {
            MDC.remove("traceId");
        }

        assertEquals("legacy", headers.getFirst(InternalServiceAuthInterceptor.KEY_ID_HEADER));
        assertNotNull(headers.getFirst(InternalServiceAuthInterceptor.TIMESTAMP_HEADER));
        assertNotNull(headers.getFirst(InternalServiceAuthInterceptor.NONCE_HEADER));
        assertEquals(64, headers.getFirst(InternalServiceAuthInterceptor.SIGNATURE_HEADER).length());
        assertEquals("stage9-trace-001", headers.getFirst("X-Trace-Id"));
        verify(execution).execute(request, body);
    }
}
