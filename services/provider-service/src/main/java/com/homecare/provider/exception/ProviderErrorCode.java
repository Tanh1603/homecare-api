package com.homecare.provider.exception;

import org.springframework.http.HttpStatus;

import com.homecare.common.error.ApiErrorCodeContract;

public enum ProviderErrorCode implements ApiErrorCodeContract {
    PROVIDER_NOT_FOUND(HttpStatus.NOT_FOUND, "error.provider.not-found", "Provider not found."),
    TECHNICIAN_NOT_FOUND(HttpStatus.NOT_FOUND, "error.technician.not-found", "Technician not found."),
    OFFERING_NOT_FOUND(HttpStatus.NOT_FOUND, "error.offering.not-found", "Service offering not found."),
    SERVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "error.service.not-found", "Service offering not found."),
    SCHEDULE_WINDOW_NOT_FOUND(HttpStatus.NOT_FOUND, "error.schedule-window.not-found", "Schedule window not found."),
    SCHEDULE_OVERLAP(HttpStatus.CONFLICT, "error.schedule.overlap", "Schedule window overlaps an existing schedule window."),
    SCHEDULE_ALLOCATION_CONFLICT(HttpStatus.CONFLICT, "error.schedule.allocation-conflict", "Cannot modify a schedule window with an active booking allocation.");

    private final HttpStatus status;
    private final String messageKey;
    private final String defaultMessage;

    ProviderErrorCode(HttpStatus status, String messageKey, String defaultMessage) {
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
