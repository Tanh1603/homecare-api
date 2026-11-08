package com.homecare.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
            @JsonAlias({"note", "notes"}) String note) {
    }

    public record BookingHistoryResponse(
            UUID id,
            String fromStatus,
            String toStatus,
            String actorId,
            String reason,
            Instant occurredAt) {
    }

    public record WorkUpdateResponse(
            UUID id,
            UUID bookingId,
            String technicianProfileId,
            String kind,
            String note,
            Instant createdAt) {
    }

    public record BookingResponse(
            UUID id,
            String requesterProfileId,
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
    }

    public record VerifyBookingReviewEligibilityRequest(
            @NotNull UUID bookingId,
            @NotBlank String requesterProfileId) {
    }

    public record VerifyBookingReviewEligibilityResponse(
            boolean eligible,
            UUID bookingId,
            UUID providerId,
            UUID serviceId,
            String status) {
    }
}
