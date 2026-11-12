package com.homecare.common.web.servlet;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;

import com.homecare.common.error.ApiErrorCodeContract;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.CorrelationIdSupport;

import jakarta.servlet.http.HttpServletRequest;

public class ServletApiResponseFactory {

    private static final String MESSAGE_ARGUMENT_ERROR = "Missing correlation ID request attribute";

    private final MessageSource messageSource;

    public ServletApiResponseFactory() {
        this(null);
    }

    public ServletApiResponseFactory(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public <T> ApiResponse<T> ok(String messageKey, T data, HttpServletRequest request) {
        return success(HttpStatus.OK, messageKey, data, request);
    }

    public <T> ApiResponse<T> created(String messageKey, T data, HttpServletRequest request) {
        return success(HttpStatus.CREATED, messageKey, data, request);
    }

    public <T> ApiResponse<T> success(
            HttpStatus status,
            String messageKey,
            T data,
            HttpServletRequest request) {
        return new ApiResponse<>(
                true,
                status.value(),
                null,
                resolveMessage(messageKey, messageKey),
                data,
                null,
                Instant.now(),
                correlationId(request));
    }

    public ApiResponse<Void> error(
            ApiErrorCodeContract errorCode,
            List<ApiResponse.FieldError> errors,
            HttpServletRequest request) {
        return error(
                errorCode.status(),
                errorCode.name(),
                resolveMessage(errorCode.messageKey(), errorCode.defaultMessage()),
                errors,
                request);
    }

    public ApiResponse<Void> error(
            HttpStatus status,
            String errorCode,
            String message,
            List<ApiResponse.FieldError> errors,
            HttpServletRequest request) {
        return new ApiResponse<>(
                false,
                status.value(),
                errorCode,
                message,
                null,
                errors == null || errors.isEmpty() ? null : errors,
                Instant.now(),
                correlationId(request));
    }

    protected String resolveMessage(String messageKey, String defaultMessage) {
        if (messageSource == null) {
            return defaultMessage != null ? defaultMessage : messageKey;
        }
        return messageSource.getMessage(messageKey, null, defaultMessage, Locale.ENGLISH);
    }

    public String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdSupport.REQUEST_ATTRIBUTE);
        if (value instanceof String correlationId) {
            return correlationId;
        }
        throw new IllegalStateException(MESSAGE_ARGUMENT_ERROR);
    }
}
