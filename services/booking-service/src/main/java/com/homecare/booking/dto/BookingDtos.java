package com.homecare.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record CreateBookingRequest(
            @JsonAlias({"serviceId", "offeringId"}) @NotNull UUID serviceId,
            UUID deviceId,
            String deviceType,
            @JsonAlias({"preferredStart", "scheduledAt"}) Instant preferredStart,
            Instant preferredEnd,
            @NotBlank String address,
            String notes,
            String bookingType,
            UUID warrantyId,
            UUID preferredProviderId) {

        public CreateBookingRequest(
                UUID serviceId,
                UUID deviceId,
                String deviceType,
                Instant preferredStart,
                Instant preferredEnd,
                String address,
                String notes) {
            this(serviceId, deviceId, deviceType, preferredStart, preferredEnd, address, notes, "STANDARD", null, null);
        }
    }

    public record CancelBookingRequest(
            String reason) {
    }

    public record CreateWorkUpdateRequest(
            @JsonAlias({"kind", "status"}) @NotBlank String kind,
            @JsonAlias({"note", "notes"}) String note,
            @PositiveOrZero Long extraCost,
            List<UUID> evidenceIds) {
        public CreateWorkUpdateRequest(String kind, String note) {
            this(kind, note, null, List.of());
        }
    }

    public record ApproveExtraCostRequest(
            boolean approved,
            String note) {
    }

    public record WorkEvidenceResponse(
            UUID id,
            UUID bookingId,
            String contentType,
            long sizeBytes,
            Instant createdAt,
            String signedUrl) {
    }

    public record BookingHistoryResponse(
            UUID id,
            String fromStatus,
            String toStatus,
            String actorId,
            String actorName,
            String reason,
            Instant occurredAt) {
        public BookingHistoryResponse(UUID id, String fromStatus, String toStatus, String actorId, String reason, Instant occurredAt) {
            this(id, fromStatus, toStatus, actorId, null, reason, occurredAt);
        }
    }

    public record WorkUpdateResponse(
            UUID id,
            UUID bookingId,
            String technicianProfileId,
            String kind,
            String note,
            Long extraCost,
            String extraCostStatus,
            List<WorkEvidenceResponse> evidence,
            Instant createdAt) {
        public WorkUpdateResponse(UUID id, UUID bookingId, String technicianProfileId,
                String kind, String note, Instant createdAt) {
            this(id, bookingId, technicianProfileId, kind, note, null, "NONE", List.of(), createdAt);
        }
    }

    public record BookingResponse(
            UUID id,
            String requesterProfileId,
            String requesterName,
            UUID serviceId,
            String serviceName,
            UUID providerId,
            String bookingType,
            UUID warrantyId,
            UUID deviceId,
            String deviceType,
            UUID assignedTechnicianId,
            String assignedTechnicianProfileId,
            String assignedTechnicianName,
            Instant preferredStart,
            Instant preferredEnd,
            String address,
            String notes,
            String status,
            String allocationStatus,
            long quotedPrice,
            Long version,
            Instant createdAt,
            Instant updatedAt,
            List<BookingHistoryResponse> history,
            List<WorkUpdateResponse> workUpdates) {
        public BookingResponse(
                UUID id, String requesterProfileId, UUID serviceId, String serviceName, UUID providerId,
                String bookingType, UUID warrantyId, UUID deviceId, String deviceType, UUID assignedTechnicianId,
                String assignedTechnicianProfileId, String assignedTechnicianName, Instant preferredStart,
                Instant preferredEnd, String address, String notes, String status, String allocationStatus,
                long quotedPrice, Long version, Instant createdAt, Instant updatedAt,
                List<BookingHistoryResponse> history, List<WorkUpdateResponse> workUpdates) {
            this(id, requesterProfileId, null, serviceId, serviceName, providerId, bookingType, warrantyId,
                    deviceId, deviceType, assignedTechnicianId, assignedTechnicianProfileId, assignedTechnicianName,
                    preferredStart, preferredEnd, address, notes, status, allocationStatus, quotedPrice, version,
                    createdAt, updatedAt, history, workUpdates);
        }
    }

    public record VerifyBookingReviewEligibilityResponse(
            boolean eligible,
            UUID bookingId,
            UUID providerId,
            UUID serviceId,
            String status) {
    }
}
