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
    SCHEDULE_ALLOCATION_CONFLICT(HttpStatus.CONFLICT, "error.schedule.allocation-conflict", "Cannot modify a schedule window with an active booking allocation."),
    ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "error.organization.not-found", "Organization not found."),
    ORGANIZATION_NOT_APPROVED(HttpStatus.FORBIDDEN, "error.organization.not-approved", "Organization is not approved for business operations."),
    ORG_MEMBERSHIP_REQUIRED(HttpStatus.FORBIDDEN, "error.organization.membership-required", "User is not an active member of this organization."),
    ORG_ROLE_FORBIDDEN(HttpStatus.FORBIDDEN, "error.organization.role-forbidden", "User does not have the required role in this organization."),
    INVALID_ORGANIZATION_STATE(HttpStatus.CONFLICT, "error.organization.invalid-state", "Invalid organization state transition."),
    MISSING_ORGANIZATION_HEADER(HttpStatus.BAD_REQUEST, "error.organization.header-missing", "X-Organization-Id header is required and must be a valid UUID."),
    MEMBER_ALREADY_EXISTS(HttpStatus.CONFLICT, "error.organization.member-exists", "User is already a member of this organization.");

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
