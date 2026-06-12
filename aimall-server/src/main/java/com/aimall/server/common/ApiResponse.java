package com.aimall.server.common;

import lombok.Data;

@Data
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    private ApiResponse() {}

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> fail(String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 1;
        r.message = message;
        r.data = null;
        return r;
    }
}
