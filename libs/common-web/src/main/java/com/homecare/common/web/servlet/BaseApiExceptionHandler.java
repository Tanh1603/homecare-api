package com.homecare.common.web.servlet;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.homecare.common.error.ApiErrorCodeContract;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.CorrelationIdSupport;
import com.homecare.common.web.EtagSupport;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

public abstract class BaseApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BaseApiExceptionHandler.class);

    protected final ServletApiResponseFactory responseFactory;

    protected BaseApiExceptionHandler(ServletApiResponseFactory responseFactory) {
        this.responseFactory = responseFactory;
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomainException(
            DomainException exception,
            HttpServletRequest request) {
        log.warn("Domain exception code={} status={} traceId={}",
                exception.errorCode(), exception.status().value(), traceId(request));
        return ResponseEntity.status(exception.status())
                .body(responseFactory.error(
                        exception.status(),
                        exception.errorCode(),
                        exception.getMessage(),
                        null,
                        request));
    }

    @ExceptionHandler(EtagSupport.PreconditionException.class)
    public ResponseEntity<ApiResponse<Void>> handlePreconditionException(
            EtagSupport.PreconditionException exception,
            HttpServletRequest request) {
        log.warn("Precondition check failed code={} traceId={}", exception.errorCode(), traceId(request));
        return ResponseEntity.status(exception.status())
                .body(responseFactory.error(
                        exception.status(),
                        exception.errorCode(),
                        exception.getMessage(),
                        null,
                        request));
    }

    @ExceptionHandler(IdempotencyService.IdempotencyException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdempotencyException(
            IdempotencyService.IdempotencyException exception,
            HttpServletRequest request) {
        log.warn("Idempotency check failed code={} traceId={}", exception.errorCode(), traceId(request));
        return ResponseEntity.status(exception.status())
                .body(responseFactory.error(
                        exception.status(),
                        exception.errorCode(),
                        exception.getMessage(),
                        null,
                        request));
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
        log.warn("Request validation failed fieldCount={} traceId={}", errors.size(), traceId(request));
        return response(CommonErrorCode.VALIDATION_FAILED, errors, request);
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
        log.warn("Constraint validation failed fieldCount={} traceId={}", errors.size(), traceId(request));
        return response(CommonErrorCode.VALIDATION_FAILED, errors, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(
            Exception exception,
            HttpServletRequest request) {
        log.warn("Request could not be parsed exceptionType={} traceId={}",
                exception.getClass().getSimpleName(), traceId(request));
        return response(CommonErrorCode.MALFORMED_REQUEST, null, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpServletRequest request) {
        log.warn("Request media type is not supported traceId={}", traceId(request));
        return response(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, null, request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnacceptableMediaType(HttpServletRequest request) {
        log.warn("Response media type is not acceptable traceId={}", traceId(request));
        return response(CommonErrorCode.MEDIA_TYPE_NOT_ACCEPTABLE, null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        log.warn("Method not allowed method={} traceId={}", safeMethod(exception.getMethod()), traceId(request));
        return response(CommonErrorCode.METHOD_NOT_ALLOWED, null, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(HttpServletRequest request) {
        log.warn("Resource not found traceId={}", traceId(request));
        return response(CommonErrorCode.RESOURCE_NOT_FOUND, null, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataConflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        log.warn("Persistence conflict exceptionType={} traceId={}",
                exception.getClass().getSimpleName(), traceId(request));
        return response(CommonErrorCode.CONFLICT, null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        log.error("Unexpected API failure exceptionType={} traceId={}",
                exception.getClass().getSimpleName(), traceId(request), exception);
        return response(CommonErrorCode.INTERNAL_ERROR, null, request);
    }

    protected ApiResponse.FieldError fieldError(FieldError error) {
        return new ApiResponse.FieldError(error.getField(), error.getDefaultMessage());
    }

    protected ApiResponse.FieldError fieldError(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        String field = path.substring(path.lastIndexOf('.') + 1);
        return new ApiResponse.FieldError(field, violation.getMessage());
    }

    protected ResponseEntity<ApiResponse<Void>> response(
            ApiErrorCodeContract errorCode,
            List<ApiResponse.FieldError> errors,
            HttpServletRequest request) {
        return ResponseEntity.status(errorCode.status())
                .body(responseFactory.error(errorCode, errors, request));
    }

    protected String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdSupport.REQUEST_ATTRIBUTE);
        return value instanceof String traceId ? traceId : "unavailable";
    }

    protected String safeMethod(String method) {
        return method == null ? "unknown" : method;
    }
}
