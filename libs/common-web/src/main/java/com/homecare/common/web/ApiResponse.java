package com.homecare.common.web;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        int code,
        String errorCode,
        String message,
        @JsonInclude(JsonInclude.Include.ALWAYS) T data,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> errors,
        Instant timestamp,
        String traceId) {

    public static <T> ApiResponse<T> ok(String message, T data, String traceId) {
        return new ApiResponse<>(true, 200, null, message, data, null, Instant.now(), traceId);
    }

    public static <T> ApiResponse<T> created(String message, T data, String traceId) {
        return new ApiResponse<>(true, 201, null, message, data, null, Instant.now(), traceId);
    }

    public static <T> ApiResponse<T> error(int code, String errorCode, String message, String traceId) {
        return new ApiResponse<>(false, code, errorCode, message, null, null, Instant.now(), traceId);
    }

    public static <T> ApiResponse<T> validationError(String message, List<FieldError> errors, String traceId) {
        return new ApiResponse<>(false, 400, "VALIDATION_FAILED", message, null, errors, Instant.now(), traceId);
    }

    public record FieldError(String field, String message) {
    }
}
