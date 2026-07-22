package com.aimall.server.common;

import com.aimall.server.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    @Test
    void methodNotSupportedUsesHttp405InsteadOfServerError() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("GET")
        );

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals("请求方法不支持", response.getBody().getMessage());
    }

    @Test
    void businessExceptionKeepsStableCodeTraceAndDetails() {
        MDC.put("traceId", "stage8-trace-001");
        try {
            GlobalExceptionHandler handler = new GlobalExceptionHandler();

            ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                    new ConflictException("REQUEST_ID_CONFLICT", "requestId already exists")
            );

            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            assertEquals(1, response.getBody().getCode());
            assertEquals("REQUEST_ID_CONFLICT", response.getBody().getErrorCode());
            assertEquals("stage8-trace-001", response.getBody().getTraceId());
            assertEquals("1.0", response.getBody().getVersion());
            assertFalse(response.getBody().getDetails().containsKey("stackTrace"));
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void unexpectedExceptionDoesNotLeakImplementationDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleException(
                new IllegalStateException("java.sql.SQLException from SecretMapper stack")
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().getErrorCode());
        assertEquals("服务器开小差了，请稍后再试", response.getBody().getMessage());
        assertNull(response.getBody().getData());
        assertFalse(response.getBody().getMessage().contains("SQLException"));
    }

    @Test
    void databaseConnectionFailureUsesRetryable503Contract() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleLegacyRuntimeException(
                new IllegalStateException("query failed", new SQLTransientConnectionException("connection refused", "08001"))
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DEPENDENCY_UNAVAILABLE", response.getBody().getErrorCode());
        assertEquals("mysql", response.getBody().getDetails().get("dependency"));
        assertEquals(true, response.getBody().getDetails().get("retryable"));
        assertFalse(response.getBody().getMessage().contains("connection refused"));
    }

    @Test
    void successResponseCarriesVersionAndTrace() {
        MDC.put("traceId", "stage8-success-001");
        try {
            ApiResponse<String> response = ApiResponse.success("ok");

            assertEquals(0, response.getCode());
            assertNull(response.getErrorCode());
            assertEquals("stage8-success-001", response.getTraceId());
            assertEquals("1.0", response.getVersion());
        } finally {
            MDC.remove("traceId");
        }
    }
}
