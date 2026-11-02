package com.homecare.identity.infrastructure;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;

public final class ApiErrors {
    private ApiErrors() {}
    public record FieldError(String field, String code, String message) {}
    public record Detail(String code, String message, List<FieldError> fieldErrors, String traceId) {}
    public record Envelope(Detail error) {}

    public static Envelope body(String code, String message, List<FieldError> fields, HttpServletRequest request) {
        return new Envelope(new Detail(code, message, fields,
                (String) request.getAttribute(CorrelationFilter.ATTRIBUTE)));
    }

    public static class InvalidInput extends RuntimeException {
        public InvalidInput() { super("Invalid request"); }
    }
}
