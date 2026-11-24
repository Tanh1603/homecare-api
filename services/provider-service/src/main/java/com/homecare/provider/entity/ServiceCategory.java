package com.homecare.provider.entity;

import java.util.Optional;

/**
 * Canonical v0.1 Service Categories (D-12, D-25).
 */
public enum ServiceCategory {
    AC_CLEANING_MAINTENANCE(false, 0, 30),
    AC_REPAIR_INSTALL(true, 7, 90),
    ELECTRICAL_REPAIR(true, 7, 90),
    PLUMBING(false, 7, 90),
    APPLIANCE_REPAIR(false, 7, 90),
    WATER_HEATER_GAS(true, 7, 90);

    private final boolean requiresQualification;
    private final int minWorkmanshipDays;
    private final int maxWorkmanshipDays;

    ServiceCategory(boolean requiresQualification, int minWorkmanshipDays, int maxWorkmanshipDays) {
        this.requiresQualification = requiresQualification;
        this.minWorkmanshipDays = minWorkmanshipDays;
        this.maxWorkmanshipDays = maxWorkmanshipDays;
    }

    public boolean requiresQualification() {
        return requiresQualification;
    }

    public int minWorkmanshipDays() {
        return minWorkmanshipDays;
    }

    public int maxWorkmanshipDays() {
        return maxWorkmanshipDays;
    }

    public boolean isValidWorkmanshipDays(int days) {
        return days >= minWorkmanshipDays && days <= maxWorkmanshipDays;
    }

    public static Optional<ServiceCategory> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ServiceCategory.valueOf(code.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
