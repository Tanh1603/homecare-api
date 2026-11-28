package com.homecare.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class BookingExecutionDtos {

    private BookingExecutionDtos() {
    }

    public record WaitingForPartsRequest(
            @NotBlank String reason) {
    }

    public record ResumeFromPartsRequest(
            UUID technicianId,
            String technicianProfileId,
            String technicianName,
            String note) {
    }

    public record CustomerNoShowRequest(
            @NotNull UUID evidenceId,
            @NotBlank String callLogNote) {
    }

    public record SubmitCompletionRequest(
            @NotBlank String workSummary,
            List<UUID> evidenceIds) {
    }

    public record AcceptBookingRequest(
            String note) {
    }

    public record RequestRevisionRequest(
            @NotBlank String reason) {
    }

    public record StartReworkRequest(
            String note) {
    }

    public record RefuseRevisionRequest(
            @NotBlank String reason) {
    }

    public record RetryCompletionNotificationRequest(
            @Size(max = 500) String note) {
    }

    public record AdminNotificationFailedBookingResponse(
            UUID bookingId,
            UUID providerId,
            String requesterProfileId,
            String serviceName,
            Instant completionSubmittedAt,
            Instant notificationFailedAt,
            int notificationRetryCount,
            Instant lastNotificationRetryAt,
            String adminReviewReason,
            Long version) {
    }

    public record AdminNotificationFailedBookingDetailResponse(
            UUID bookingId,
            UUID providerId,
            String requesterProfileId,
            String serviceName,
            String status,
            String completionSummary,
            int workEvidenceCount,
            Instant completionSubmittedAt,
            Instant notificationFailedAt,
            int notificationRetryCount,
            Instant lastNotificationRetryAt,
            String adminReviewReason,
            Long version) {
    }
}
