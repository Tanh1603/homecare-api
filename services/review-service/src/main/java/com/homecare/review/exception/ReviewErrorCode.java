package com.homecare.review.exception;

import org.springframework.http.HttpStatus;

import com.homecare.common.error.ApiErrorCodeContract;

public enum ReviewErrorCode implements ApiErrorCodeContract {
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "error.review.not-found", "Review not found."),
    REVIEW_NOT_ELIGIBLE(HttpStatus.CONFLICT, "error.review.not-eligible", "User is not eligible to review this item."),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "error.review.already-exists", "A review for this item already exists.");

    private final HttpStatus status;
    private final String messageKey;
    private final String defaultMessage;

    ReviewErrorCode(HttpStatus status, String messageKey, String defaultMessage) {
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
