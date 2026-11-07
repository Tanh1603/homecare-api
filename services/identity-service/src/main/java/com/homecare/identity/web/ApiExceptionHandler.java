package com.homecare.identity.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.BaseApiExceptionHandler;
import com.homecare.identity.exception.IdentityException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiExceptionHandler extends BaseApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public ApiExceptionHandler(ApiResponseFactory responseFactory) {
        super(responseFactory);
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdentityException(
            IdentityException exception,
            HttpServletRequest request) {
        log.warn("Identity request failed code={} traceId={}",
                exception.getErrorCode().name(), traceId(request));
        return response(exception.getErrorCode(), null, request);
    }
}
