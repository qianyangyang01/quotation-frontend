package com.milano.quotation.common;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static AppException notFound(String message) { return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }
    public static AppException conflict(String message) { return new AppException(HttpStatus.CONFLICT, "CONFLICT", message); }
    public static AppException unprocessable(String message) { return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", message); }
}
