package com.homecare.identity.dto;

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
        List<FieldError> errors,
        Instant timestamp,
        String traceId) {

    public record FieldError(String field, String message) {
    }
}
