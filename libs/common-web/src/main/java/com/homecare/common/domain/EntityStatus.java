package com.homecare.common.domain;

/**
 * Shared lifecycle and outbox status constants used across services.
 */
public enum EntityStatus {
    ACTIVE,
    INACTIVE,
    PENDING,
    PUBLISHED
}
