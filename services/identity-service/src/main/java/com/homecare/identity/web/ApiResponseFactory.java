package com.homecare.identity.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.homecare.identity.dto.ApiResponse;
import com.homecare.identity.exception.ApiErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ApiResponseFactory {

    private static final String MESSAGE_ARGUMENT_ERROR = "Missing correlation ID request attribute";

    private final MessageSource messageSource;

    public <T> ApiResponse<T> success(
            HttpStatus status,
            String messageKey,
            T data,
            HttpServletRequest request) {
        return new ApiResponse<>(
                true,
                status.value(),
                null,
                message(messageKey),
                data,
                null,
                Instant.now(),
                correlationId(request));
    }

    public ApiResponse<Void> error(
            ApiErrorCode errorCode,
            List<ApiResponse.FieldError> errors,
            HttpServletRequest request) {
        return new ApiResponse<>(
                false,
                errorCode.status().value(),
                errorCode.name(),
                message(errorCode.messageKey()),
                null,
                errors == null || errors.isEmpty() ? null : errors,
                Instant.now(),
                correlationId(request));
    }

    private String message(String messageKey) {
        return messageSource.getMessage(messageKey, null, Locale.ENGLISH);
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        if (!(value instanceof String correlationId)) {
            throw new IllegalStateException(MESSAGE_ARGUMENT_ERROR);
        }
        return correlationId;
    }
}
