package com.homecare.identity.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(com.homecare.identity.profile.ProfileFailure.class)
    ResponseEntity<Object> profile(com.homecare.identity.profile.ProfileFailure ex, HttpServletRequest request) {
        var headers = new HttpHeaders();
        if (ex.code.equals("IDEMPOTENCY_IN_PROGRESS")) headers.set("Retry-After", "1");
        return new ResponseEntity<>(ApiErrors.body(ex.code, "Yeu cau khong the thuc hien.", List.of(), request), headers, ex.status);
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.CannotCreateTransactionException.class})
    ResponseEntity<Object> unavailable(RuntimeException ex, HttpServletRequest request) {
        return ResponseEntity.status(503).body(ApiErrors.body("IDENTITY_UNAVAILABLE", "Dich vu tam thoi khong san sang.", List.of(), request));
    }
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiErrors.FieldError(f.getField(), "INVALID", "Truong du lieu khong hop le."))
                .distinct().toList();
        return response(status, headers, fields, ((ServletWebRequest) request).getRequest());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return response(status, headers, List.of(), ((ServletWebRequest) request).getRequest());
    }

    @ExceptionHandler(ApiErrors.InvalidInput.class)
    ResponseEntity<Object> invalid(ApiErrors.InvalidInput ex, HttpServletRequest request) {
        return response(HttpStatusCode.valueOf(400), new HttpHeaders(), List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, HttpServletRequest request) {
        // Never log exception messages/stack traces that can contain SQL, credentials or input.
        LoggerFactory.getLogger(getClass()).error("request_failed correlationId={} type={}",
                request.getAttribute(CorrelationFilter.ATTRIBUTE), ex.getClass().getSimpleName());
        return response(HttpStatusCode.valueOf(500), new HttpHeaders(), List.of(), request);
    }

    private ResponseEntity<Object> response(HttpStatusCode status, HttpHeaders headers,
            List<ApiErrors.FieldError> fields, HttpServletRequest request) {
        String code = switch (status.value()) {
            case 400 -> "REQUEST_VALIDATION_FAILED";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "OPERATION_FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INTERNAL_ERROR";
        };
        return new ResponseEntity<>(ApiErrors.body(code, status.value() < 500
                ? "Yeu cau khong hop le." : "Khong the xu ly yeu cau.", fields, request), headers, status);
    }
}
