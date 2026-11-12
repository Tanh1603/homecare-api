package com.homecare.notification.exception;

import org.springframework.http.HttpStatus;

import com.homecare.common.error.ApiErrorCodeContract;

public enum NotificationErrorCode implements ApiErrorCodeContract {
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "error.notification.not-found", "Notification not found."),
    DEVICE_REGISTRATION_NOT_FOUND(HttpStatus.NOT_FOUND, "error.device-registration.not-found", "Device registration not found."),
    TOKEN_ALREADY_REGISTERED(HttpStatus.CONFLICT, "error.notification.token-already-registered", "Push token is already registered to another user.");

    private final HttpStatus status;
    private final String messageKey;
    private final String defaultMessage;

    NotificationErrorCode(HttpStatus status, String messageKey, String defaultMessage) {
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
