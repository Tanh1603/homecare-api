package com.homecare.identity.web;

import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import com.homecare.identity.dto.ApiErrorResponse;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ApiErrorResponseFactory {

    private final MessageSource messageSource;

    public ApiErrorResponse create(
            ApiErrorCode errorCode,
            List<ApiErrorResponse.FieldErrorDetail> fieldErrors,
            HttpServletRequest request) {
        String message = messageSource.getMessage(
                errorCode.messageKey(),
                null,
                Locale.ENGLISH);
        return new ApiErrorResponse(new ApiErrorResponse.ErrorDetail(
                errorCode.name(),
                message,
                fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors,
                correlationId(request)));
    }

    public ApiErrorResponse create(IdentityException exception, HttpServletRequest request) {
        return create(exception.getErrorCode(), null, request);
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String correlationId ? correlationId : null;
    }
}
