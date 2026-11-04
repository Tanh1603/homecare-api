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

import com.homecare.identity.dto.ApiErrorResponse;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiErrorResponseFactory responseFactory;

    public ApiExceptionHandler(ApiErrorResponseFactory responseFactory) {
        this.responseFactory = responseFactory;
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<ApiErrorResponse> handleIdentityException(
            IdentityException exception,
            HttpServletRequest request) {
        log.warn("Identity request failed code={} traceId={}",
                exception.getErrorCode().name(), traceId(request));
        return response(exception.getErrorCode(), null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidArguments(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiErrorResponse.FieldErrorDetail> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::fieldError)
                .sorted(Comparator.comparing(ApiErrorResponse.FieldErrorDetail::field))
                .toList();
        log.warn("Identity request validation failed fieldCount={} traceId={}",
                fieldErrors.size(), traceId(request));
        return response(ApiErrorCode.REQUEST_VALIDATION_FAILED, fieldErrors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<ApiErrorResponse.FieldErrorDetail> fieldErrors = exception.getConstraintViolations()
                .stream()
                .map(this::fieldError)
                .sorted(Comparator.comparing(ApiErrorResponse.FieldErrorDetail::field))
                .toList();
        log.warn("Identity constraint validation failed fieldCount={} traceId={}",
                fieldErrors.size(), traceId(request));
        return response(ApiErrorCode.REQUEST_VALIDATION_FAILED, fieldErrors, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(
            Exception exception,
            HttpServletRequest request) {
        log.warn("Identity request could not be parsed exceptionType={} traceId={}",
                exception.getClass().getSimpleName(), traceId(request));
        return response(ApiErrorCode.MALFORMED_REQUEST, null, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpServletRequest request) {
        log.warn("Identity request media type is not supported traceId={}", traceId(request));
        return response(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE, null, request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnacceptableMediaType(HttpServletRequest request) {
        log.warn("Identity response media type is not acceptable traceId={}", traceId(request));
        return response(ApiErrorCode.MEDIA_TYPE_NOT_ACCEPTABLE, null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        log.warn("Identity method not allowed method={} traceId={}",
                safeMethod(exception.getMethod()), traceId(request));
        return response(ApiErrorCode.METHOD_NOT_ALLOWED, null, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(HttpServletRequest request) {
        log.warn("Identity resource not found traceId={}", traceId(request));
        return response(ApiErrorCode.RESOURCE_NOT_FOUND, null, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataConflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        log.warn("Identity persistence conflict exceptionType={} traceId={}",
                exception.getClass().getSimpleName(), traceId(request));
        return response(ApiErrorCode.CONFLICT, null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        log.error("Unexpected Identity API failure exceptionType={} traceId={}",
                exception.getClass().getSimpleName(), traceId(request), exception);
        return response(ApiErrorCode.INTERNAL_ERROR, null, request);
    }

    private ApiErrorResponse.FieldErrorDetail fieldError(FieldError error) {
        String code = fieldCode(error.getCode());
        String message = error.getDefaultMessage();
        return new ApiErrorResponse.FieldErrorDetail(error.getField(), code, message);
    }

    private ApiErrorResponse.FieldErrorDetail fieldError(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        String field = path.substring(path.lastIndexOf('.') + 1);
        String annotation = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return new ApiErrorResponse.FieldErrorDetail(
                field,
                fieldCode(annotation),
                violation.getMessage());
    }

    private String fieldCode(String validationCode) {
        if (validationCode == null) {
            return "INVALID_VALUE";
        }
        return switch (validationCode) {
            case "NotBlank", "NotNull", "NotEmpty" -> "REQUIRED";
            case "Email", "Pattern" -> "INVALID_FORMAT";
            case "Size", "Min", "Max", "Length" -> "SIZE_OR_RANGE_INVALID";
            default -> "INVALID_VALUE";
        };
    }

    private ResponseEntity<ApiErrorResponse> response(
            ApiErrorCode errorCode,
            List<ApiErrorResponse.FieldErrorDetail> fieldErrors,
            HttpServletRequest request) {
        return ResponseEntity.status(errorCode.status())
                .body(responseFactory.create(errorCode, fieldErrors, request));
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String traceId ? traceId : "unavailable";
    }

    private String safeMethod(String method) {
        return method == null ? "unknown" : method;
    }
}
