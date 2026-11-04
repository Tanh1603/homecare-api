package com.homecare.identity.exception;

public class IdentityException extends RuntimeException {

    private final ApiErrorCode errorCode;

    public IdentityException(ApiErrorCode errorCode) {
        super(errorCode.name());
        this.errorCode = errorCode;
    }

    public IdentityException(ApiErrorCode errorCode, Throwable cause) {
        super(errorCode.name(), cause);
        this.errorCode = errorCode;
    }

    public ApiErrorCode getErrorCode() {
        return errorCode;
    }
}
