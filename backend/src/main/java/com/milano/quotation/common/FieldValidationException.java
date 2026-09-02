package com.milano.quotation.common;

import java.util.List;

public class FieldValidationException extends RuntimeException {
    private final List<ApiResponse.FieldError> fieldErrors;
    public FieldValidationException(List<ApiResponse.FieldError> fieldErrors) {
        super("输入数据不符合要求");
        this.fieldErrors = List.copyOf(fieldErrors);
    }
    public List<ApiResponse.FieldError> fieldErrors() { return fieldErrors; }
}
