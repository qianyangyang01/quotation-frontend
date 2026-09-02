package com.milano.quotation.common;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.milano.quotation.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final AuditService audit;
    public GlobalExceptionHandler(AuditService audit) { this.audit = audit; }
    @ExceptionHandler(AppException.class)
    ResponseEntity<ApiResponse<Void>> app(AppException exception) {
        return ResponseEntity.status(exception.status()).body(ApiResponse.error(exception.code(), exception.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException exception) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiResponse.FieldError(error.getField(), error.getDefaultMessage())).toList();
        return ResponseEntity.unprocessableEntity().body(ApiResponse.error("VALIDATION_ERROR", "输入数据不符合要求", errors));
    }

    @ExceptionHandler(FieldValidationException.class)
    ResponseEntity<ApiResponse<Void>> fieldValidation(FieldValidationException exception) {
        return ResponseEntity.unprocessableEntity().body(ApiResponse.error("VALIDATION_ERROR", exception.getMessage(), exception.fieldErrors()));
    }

    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class,
            DataIntegrityViolationException.class})
    ResponseEntity<ApiResponse<Void>> conflict(Exception ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("CONFLICT", "数据已变化或存在重复，请刷新后重试", List.of()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> forbidden() {
        audit.record("security.access-denied", "api", null, "failure", java.util.Map.of());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("FORBIDDEN", "没有执行该操作的权限", List.of()));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiResponse<Void>> malformed(Exception ignored) {
        return ResponseEntity.unprocessableEntity().body(ApiResponse.error("VALIDATION_ERROR", "请求头或请求内容格式不正确", List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", "接口或数据不存在", List.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error("PAYLOAD_TOO_LARGE", "上传文件超过允许大小", List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        log.error("Unhandled quotation API failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("INTERNAL_ERROR", "服务器处理失败，请凭请求编号联系管理员", List.of()));
    }
}
