package com.homecare.booking.exception;

import org.springframework.http.HttpStatus;

import com.homecare.common.error.ApiErrorCodeContract;

public enum BookingErrorCode implements ApiErrorCodeContract {
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "error.booking.not-found", "Booking not found."),
    BOOKING_INVALID_STATE(HttpStatus.CONFLICT, "error.booking.invalid-state", "Booking is not in a valid state for this action."),
    SERVICE_NOT_AVAILABLE(HttpStatus.CONFLICT, "error.booking.service-unavailable", "Requested service offering is inactive or unavailable."),
    DEVICE_REFERENCE_INVALID(HttpStatus.CONFLICT, "error.booking.device-invalid", "Referenced device does not belong to requester or is inactive."),
    WARRANTY_EXPIRED(HttpStatus.CONFLICT, "error.booking.warranty-expired", "Warranty has expired; please book a paid repair service instead."),
    WARRANTY_INVALID(HttpStatus.CONFLICT, "error.booking.warranty-invalid", "Referenced warranty does not belong to requester or is invalid.");

    private final HttpStatus status;
    private final String messageKey;
    private final String defaultMessage;

    BookingErrorCode(HttpStatus status, String messageKey, String defaultMessage) {
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
