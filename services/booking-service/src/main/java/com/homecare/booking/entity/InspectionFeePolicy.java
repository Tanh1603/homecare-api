package com.homecare.booking.entity;

public enum InspectionFeePolicy {
    NO_FEE,
    CREDIT_FULL,
    NON_CREDITABLE;

    public static InspectionFeePolicy parseOrDefault(String raw, long inspectionFee) {
        if (raw == null || raw.isBlank()) {
            return inspectionFee > 0 ? CREDIT_FULL : NO_FEE;
        }
        return InspectionFeePolicy.valueOf(raw.trim().toUpperCase());
    }
}
