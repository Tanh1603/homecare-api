package com.homecare.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class WarrantyClaimDtos {

    private WarrantyClaimDtos() {
    }

    public record CommitmentSnapshotResponse(
            UUID id,
            UUID bookingId,
            UUID quotationId,
            int quotationVersion,
            String serviceCategory,
            int workmanshipDurationDays,
            String committedPartsJson,
            String policySummary,
            Instant committedAt,
            String committedByProfileId) {
    }

    public record ActivationSnapshotResponse(
            UUID id,
            UUID bookingId,
            UUID commitmentSnapshotId,
            int workmanshipDurationDays,
            Instant startsAt,
            Instant endsAt,
            String actualPartsJson,
            String excludedScopeJson,
            boolean materialDiscrepancy,
            String discrepancySummary,
            Instant createdAt) {
    }

    public record BookingWarrantySnapshotsResponse(
            UUID bookingId,
            List<CommitmentSnapshotResponse> commitmentSnapshots,
            ActivationSnapshotResponse activationSnapshot) {
    }

    public record SubmitWarrantyClaimRequest(
            UUID bookingId,
            UUID deviceId,
            UUID householdWarrantyId,
            UUID providerId,
            @NotBlank String warrantyCategory,
            @NotBlank String warrantyIssuer,
            @Size(max = 160) String referenceSerial,
            @NotBlank @Size(max = 2000) String issueDescription,
            List<String> warrantyEvidenceRefs) {
    }

    public record AppendClaimEvidenceRequest(
            @NotBlank String evidenceKind,
            @NotBlank @Size(max = 1000) String evidenceRef) {
    }

    public record RespondWarrantyClaimRequest(
            @NotNull Boolean accepted,
            Instant preferredStart,
            Instant preferredEnd,
            @Size(max = 1000) String rejectionReason) {
    }

    public record AdminVerifyWarrantyClaimRequest(
            String warrantyVerificationStatus,
            String authorizationVerificationStatus,
            @Size(max = 1000) String note) {
    }

    public record WarrantyClaimResponse(
            UUID id,
            UUID bookingId,
            UUID deviceId,
            UUID householdWarrantyId,
            UUID providerId,
            String requesterProfileId,
            String warrantyCategory,
            String warrantyIssuer,
            String referenceSerial,
            String issueDescription,
            List<String> warrantyEvidenceRefs,
            List<String> authorizationEvidenceRefs,
            String claimStatus,
            String warrantyVerificationStatus,
            String authorizationVerificationStatus,
            String validityStatus,
            Instant reportedAt,
            Instant providerResponseDeadlineAt,
            boolean providerNoResponse,
            UUID reworkBookingId,
            String verifiedBy,
            Instant verifiedAt,
            String rejectionReason,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
    }
}
