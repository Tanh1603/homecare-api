package com.homecare.review.exception;

import org.springframework.http.HttpStatus;

import com.homecare.common.error.ApiErrorCodeContract;

public enum ReviewErrorCode implements ApiErrorCodeContract {
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "error.review.not-found", "Review not found."),
    REVIEW_NOT_ELIGIBLE(HttpStatus.CONFLICT, "error.review.not-eligible", "User is not eligible to review this item."),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "error.review.already-exists", "A review for this item already exists."),
    REVIEW_WINDOW_EXPIRED(HttpStatus.CONFLICT, "error.review.window-expired", "The 30-day review window has expired."),
    REVIEW_EDIT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "error.review.edit-limit-exceeded", "This review has already been edited once."),
    REVIEW_EDIT_WINDOW_EXPIRED(HttpStatus.CONFLICT, "error.review.edit-window-expired", "The 7-day edit window has expired."),
    REVIEW_PERMANENTLY_DELETED(HttpStatus.CONFLICT, "error.review.permanently-deleted", "Deleted service reviews cannot be re-submitted."),
    REVIEW_HIDDEN(HttpStatus.CONFLICT, "error.review.hidden", "This review was hidden by moderation and cannot be modified."),
    REVIEW_INVALID_STATE(HttpStatus.CONFLICT, "error.review.invalid-state", "Review is not in a valid state for this operation.");

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
