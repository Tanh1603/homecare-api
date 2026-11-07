package com.homecare.identity.exception;

import org.springframework.http.HttpStatus;

import com.homecare.common.error.ApiErrorCodeContract;

public enum ApiErrorCode implements ApiErrorCodeContract {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "error.request.validation"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "error.request.malformed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "error.media-type.unsupported"),
    MEDIA_TYPE_NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "error.media-type.not-acceptable"),
    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "error.username.exists"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "error.email.exists"),
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "error.profile.not-found"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "error.user.not-found"),
    EMAIL_ALREADY_VERIFIED(HttpStatus.CONFLICT, "error.email.already-verified"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "error.credentials.invalid"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "error.unauthenticated"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "error.refresh-token.invalid"),
    EMAIL_VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN, "error.email.verification-required"),
    AUTH_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "error.auth-provider.unavailable"),
    OPERATION_FAILED(HttpStatus.BAD_GATEWAY, "error.dependency.operation-failed"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "error.access-denied"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "error.method-not-allowed"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "error.resource-not-found"),
    CONFLICT(HttpStatus.CONFLICT, "error.conflict"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal");

    private final HttpStatus status;
    private final String messageKey;

    ApiErrorCode(HttpStatus status, String messageKey) {
        this.status = status;
        this.messageKey = messageKey;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String messageKey() {
        return messageKey;
    }
}
