package com.homecare.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public final class DisputeDtos {

    private DisputeDtos() {
    }

    public record OpenDisputeRequest(
            @NotBlank String category,
            @NotBlank String description,
            List<UUID> evidenceIds) {
    }

    public record ProviderDisputeResponseRequest(
            @NotBlank String response,
            List<UUID> evidenceIds) {
    }

    public record RequestSlaActionRequest(
            String note) {
    }

    public record ProposeResolutionRequest(
            @NotBlank String proposal,
            @NotBlank String outcome) {
    }

    public record ResolveDisputeRequest(
            @NotBlank String outcome,
            String bookingAction,
            @NotBlank String note) {
    }

    public record DisputeEvidenceResponse(
            UUID id,
            UUID disputeId,
            String uploadedByProfileId,
            String uploaderRole,
            UUID workEvidenceId,
            String contentType,
            long sizeBytes,
            String note,
            String signedUrl,
            Instant createdAt) {
    }

    public record DisputeResponse(
            UUID id,
            UUID bookingId,
            String openedByProfileId,
            UUID providerId,
            String openedStage,
            String category,
            String description,
            String status,
            String providerResponse,
            Instant providerRespondedAt,
            boolean providerNoResponse,
            Instant slaDueAt,
            int slaReminderStage,
            String resolutionProposal,
            String resolutionOutcome,
            String resolvedByAdminId,
            String resolutionNote,
            Long version,
            Instant createdAt,
            Instant updatedAt,
            Instant closedAt,
            List<DisputeEvidenceResponse> evidence) {
    }
}
