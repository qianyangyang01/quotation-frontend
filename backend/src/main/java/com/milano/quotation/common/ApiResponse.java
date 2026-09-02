package com.milano.quotation.common;

import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;

public record ApiResponse<T>(String requestId, String code, String message, T data, List<FieldError> fieldErrors, Instant timestamp) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(currentRequestId(), "SUCCESS", "操作成功", data, List.of(), Instant.now());
    }

    public static ApiResponse<Void> error(String code, String message, List<FieldError> errors) {
        return new ApiResponse<>(currentRequestId(), code, message, null, errors, Instant.now());
    }

    private static String currentRequestId() {
        return MDC.get("requestId") == null ? "unknown" : MDC.get("requestId");
    }

    public record FieldError(String field, String message) {}
}
