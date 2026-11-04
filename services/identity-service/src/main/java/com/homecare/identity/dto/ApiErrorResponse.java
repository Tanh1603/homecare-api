package com.homecare.identity.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(ErrorDetail error) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ErrorDetail(
            String code,
            String message,
            List<FieldErrorDetail> fieldErrors,
            String traceId) {
    }

    public record FieldErrorDetail(String field, String code, String message) {
    }
}
