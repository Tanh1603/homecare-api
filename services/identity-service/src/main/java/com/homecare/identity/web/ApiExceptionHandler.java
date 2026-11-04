package com.homecare.identity.web;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.homecare.identity.dto.ApiResponse;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiResponseFactory responseFactory;

    public ApiExceptionHandler(ApiResponseFactory responseFactory) {
        this.responseFactory = responseFactory;
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdentityException(
            IdentityException exception,
            HttpServletRequest request) {
        log.warn("Identity request failed code={} traceId={}",
                exception.getErrorCode().name(), traceId(request));
        return response(exception.getErrorCode(), null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidArguments(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiResponse.FieldError> errors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::fieldError)
                .sorted(Comparator.comparing(ApiResponse.FieldError::field))
                .toList();
        log.warn("Identity request validation failed fieldCount={} traceId={}",
                errors.size(), traceId(request));
        return response(ApiErrorCode.VALIDATION_FAILED, errors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<ApiResponse.FieldError> errors = exception.getConstraintViolations()
                .stream()
                .map(this::fieldError)
                .sorted(Comparator.comparing(ApiResponse.FieldError::field))
                .toList();
        log.warn("Identity constraint validation failed fieldCount={} traceId={}",
                errors.size(), traceId(request));
        return response(ApiErrorCode.VALIDATION_FAILED, errors, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(
            Exception exception,
            HttpServletRequest request) {
        log.warn("Identity request could not be parsed exceptionType={} traceId={}",
                exception.getClass().getSimpleName(), traceId(request));
        return response(ApiErrorCode.MALFORMED_REQUEST, null, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpServletRequest request) {
        log.warn("Identity request media type is not supported traceId={}", traceId(request));
        return response(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE, null, request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnacceptableMediaType(HttpServletRequest request) {
        log.warn("Identity response media type is not acceptable traceId={}", traceId(request));
        return response(ApiErrorCode.MEDIA_TYPE_NOT_ACCEPTABLE, null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        log.warn("Identity method not allowed method={} traceId={}",
                safeMethod(exception.getMethod()), traceId(request));
        return response(ApiErrorCode.METHOD_NOT_ALLOWED, null, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(HttpServletRequest request) {
        log.warn("Identity resource not found traceId={}", traceId(request));
        return response(ApiErrorCode.RESOURCE_NOT_FOUND, null, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataConflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        log.warn("Identity persistence conflict exceptionType={} traceId={}",
                exception.getClass().getSimpleName(), traceId(request));
        return response(ApiErrorCode.CONFLICT, null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        log.error("Unexpected Identity API failure exceptionType={} traceId={}",
                exception.getClass().getSimpleName(), traceId(request), exception);
        return response(ApiErrorCode.INTERNAL_ERROR, null, request);
    }

    private ApiResponse.FieldError fieldError(FieldError error) {
        return new ApiResponse.FieldError(error.getField(), error.getDefaultMessage());
    }

    private ApiResponse.FieldError fieldError(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        String field = path.substring(path.lastIndexOf('.') + 1);
        return new ApiResponse.FieldError(field, violation.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> response(
            ApiErrorCode errorCode,
            List<ApiResponse.FieldError> errors,
            HttpServletRequest request) {
        return ResponseEntity.status(errorCode.status())
                .body(responseFactory.error(errorCode, errors, request));
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String traceId ? traceId : "unavailable";
    }

    private String safeMethod(String method) {
        return method == null ? "unknown" : method;
    }
}
