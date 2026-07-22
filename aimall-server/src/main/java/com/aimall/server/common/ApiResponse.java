package com.aimall.server.common;

import lombok.Data;
import org.slf4j.MDC;

import java.util.Map;

@Data
public class ApiResponse<T> {
    private static final String API_VERSION = "1.0";

    private int code;
    private String errorCode;
    private String message;
    private T data;
    private String traceId;
    private Map<String, Object> details;
    private String version;

    private ApiResponse() {
    }

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = base();
        response.code = 0;
        response.errorCode = null;
        response.message = "success";
        response.data = data;
        response.details = Map.of();
        return response;
    }

    public static <T> ApiResponse<T> fail(String message) {
        return fail("BUSINESS_ERROR", message, Map.of());
    }

    public static <T> ApiResponse<T> fail(String errorCode, String message, Map<String, Object> details) {
        ApiResponse<T> response = base();
        response.code = 1;
        response.errorCode = errorCode;
        response.message = message;
        response.data = null;
        response.details = details == null ? Map.of() : Map.copyOf(details);
        return response;
    }

    private static <T> ApiResponse<T> base() {
        ApiResponse<T> response = new ApiResponse<>();
        response.traceId = MDC.get("traceId");
        response.version = API_VERSION;
        return response;
    }
}
