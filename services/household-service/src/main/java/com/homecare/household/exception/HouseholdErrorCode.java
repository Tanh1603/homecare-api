package com.homecare.household.exception;

import org.springframework.http.HttpStatus;

import com.homecare.common.error.ApiErrorCodeContract;

public enum HouseholdErrorCode implements ApiErrorCodeContract {
    HOUSEHOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "error.household.not-found", "Household not found."),
    DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "error.device.not-found", "Device not found."),
    WARRANTY_NOT_FOUND(HttpStatus.NOT_FOUND, "error.warranty.not-found", "Warranty not found."),
    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "error.schedule.not-found", "Maintenance schedule not found."),
    CARE_RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "error.care-record.not-found", "Care record not found."),
    DATE_ORDER_INVALID(HttpStatus.CONFLICT, "error.warranty.date-order-invalid", "Warranty start date must be before end date."),
    SCHEDULE_ALREADY_NOTIFIED(HttpStatus.CONFLICT, "error.schedule.already-notified", "Schedule window has already been notified.");

    private final HttpStatus status;
    private final String messageKey;
    private final String defaultMessage;

    HouseholdErrorCode(HttpStatus status, String messageKey, String defaultMessage) {
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
