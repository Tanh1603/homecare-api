package com.homecare.common.error;

import org.springframework.http.HttpStatus;

public enum CommonErrorCode implements ApiErrorCodeContract {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "error.request.validation", "The request contains invalid data."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "error.request.malformed", "The request body or parameters are malformed."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "error.media-type.unsupported", "The request media type is not supported."),
    MEDIA_TYPE_NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "error.media-type.not-acceptable", "No acceptable response media type is available."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "error.unauthenticated", "Authentication is required to access this resource."),
    TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "error.token.revoked", "Token has been revoked"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "error.access-denied", "You are not authorized to perform this operation."),
    OPERATION_FORBIDDEN(HttpStatus.FORBIDDEN, "error.operation.forbidden", "You do not have permission to perform this operation."),
    RESOURCE_FORBIDDEN(HttpStatus.FORBIDDEN, "error.resource.forbidden", "You do not have permission to access this resource."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "error.method-not-allowed", "The HTTP method is not supported for this resource."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "error.resource-not-found", "The requested resource was not found."),
    CONFLICT(HttpStatus.CONFLICT, "error.conflict", "The request conflicts with the current resource state."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "error.idempotency.key-reused", "Idempotency-Key was already used with a different request payload."),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "error.idempotency.in-progress", "A request with this Idempotency-Key is currently in progress."),
    RESOURCE_VERSION_MISMATCH(HttpStatus.PRECONDITION_FAILED, "error.precondition.failed", "The resource version does not match the provided If-Match header."),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "error.precondition.required", "The If-Match header is required for this update."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal", "An unexpected server error occurred. Please try again later.");

    private final HttpStatus status;
    private final String messageKey;
    private final String defaultMessage;

    CommonErrorCode(HttpStatus status, String messageKey, String defaultMessage) {
        this.status = status;
        this.messageKey = messageKey;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String messageKey() {
        return messageKey;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
