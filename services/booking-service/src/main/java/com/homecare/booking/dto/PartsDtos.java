package com.homecare.booking.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PartsDtos {

    private PartsDtos() {
    }

    public record CreatePartsRequest(
            @NotBlank @Size(max = 255) String partName,
            @Size(max = 120) String partCode,
            @Min(1) int quantity,
            @NotBlank String partSource,
            @Size(max = 255) String supplierName,
            @Min(0) Long costPrice,
            @Min(0) Long unitPrice,
            @Size(max = 1000) String compatibilityNote,
            @Size(max = 1000) String evidenceRef) {
    }

    public record ReviewPartsRequest(
            @NotNull Boolean approved,
            @Min(0) Long costPrice,
            @Min(0) Long unitPrice,
            @Size(max = 1000) String reason) {
    }

    public record RefuseCustomerPartRequest(
            @NotBlank @Size(max = 1000) String refusalReason,
            @Size(max = 1000) String evidenceRef) {
    }

    public record CancelPartsRequest(
            @Size(max = 1000) String reason) {
    }

    public record RecordPartsUsageRequest(
            UUID partsRequestId,
            @NotBlank @Size(max = 255) String partName,
            @Size(max = 120) String partCode,
            @Min(1) int quantity,
            @NotBlank String partSource,
            @Min(0) Long unitPrice,
            String partsWarrantyStatus,
            String warrantyIssuer,
            @Min(0) Integer warrantyDurationDays,
            @Size(max = 1000) String warrantyTerms,
            @Size(max = 1000) String evidenceRef,
            @Size(max = 1000) String purchaseProofRef) {
    }

    public record PartsRequestResponse(
            UUID id,
            UUID bookingId,
            UUID providerId,
            String requestedByProfileId,
            String partName,
            String partCode,
            int quantity,
            String partSource,
            String supplierName,
            Long costPrice,
            Long unitPrice,
            String status,
            String compatibilityNote,
            String refusalReason,
            String evidenceRef,
            String approvedByProfileId,
            Instant approvedAt,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PartsUsageResponse(
            UUID id,
            UUID bookingId,
            UUID partsRequestId,
            String partName,
            String partCode,
            int quantity,
            String partSource,
            long unitPrice,
            String partsWarrantyStatus,
            String warrantyIssuer,
            Integer warrantyDurationDays,
            String warrantyTerms,
            String evidenceRef,
            String purchaseProofRef,
            String recordedByProfileId,
            Instant createdAt) {
    }
}
