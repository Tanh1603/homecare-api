package com.homecare.common.error;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public DomainException(ApiErrorCodeContract contract) {
        super(contract.defaultMessage());
        this.status = contract.status();
        this.errorCode = contract.name();
    }

    public DomainException(ApiErrorCodeContract contract, String customMessage) {
        super(customMessage != null && !customMessage.isBlank() ? customMessage : contract.defaultMessage());
        this.status = contract.status();
        this.errorCode = contract.name();
    }

    public DomainException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }
}
