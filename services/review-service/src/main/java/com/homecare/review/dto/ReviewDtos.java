package com.homecare.review.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record CreateServiceReviewRequest(
            @NotNull UUID bookingId,
            UUID providerId,
            @Min(1) @Max(5) int rating,
            @NotBlank @Size(max = 1500) String comment) {
    }

    public record CreateProductReviewRequest(
            @NotNull UUID orderLineId,
            @Min(1) @Max(5) int rating,
            @NotBlank @Size(max = 1500) String comment) {
    }

    public record UpdateReviewRequest(
            @Min(1) @Max(5) Integer rating,
            @Size(max = 1500) String comment) {
    }

    public record ReviewResponse(
            UUID id,
            String authorProfileId,
            String authorName,
            String targetType,
            UUID sourceId,
            UUID targetId,
            int rating,
            String comment,
            String status,
            int editCount,
            Instant editedAt,
            Instant editableUntil,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
        public ReviewResponse(UUID id, String authorProfileId, String authorName, String targetType,
                UUID sourceId, UUID targetId, int rating, String comment, String status,
                Instant createdAt, Instant updatedAt) {
            this(id, authorProfileId, authorName, targetType, sourceId, targetId, rating, comment, status,
                    0, null, null, 0L, createdAt, updatedAt);
        }

        public ReviewResponse(UUID id, String authorProfileId, String targetType, UUID sourceId, UUID targetId,
                int rating, String comment, String status, Instant createdAt, Instant updatedAt) {
            this(id, authorProfileId, null, targetType, sourceId, targetId, rating, comment, status,
                    0, null, null, 0L, createdAt, updatedAt);
        }
    }

    public record ServiceReviewEligibilityResponse(
            boolean eligible,
            String reasonCode,
            Instant acceptedAt,
            Instant reviewDeadlineAt,
            ReviewResponse existingReview) {
    }

    public record AdminReviewResponse(
            UUID id,
            UUID bookingId,
            UUID providerId,
            String authorProfileId,
            String authorName,
            int rating,
            String comment,
            String status,
            int editCount,
            Instant editedAt,
            String hiddenReason,
            String hiddenBy,
            Instant hiddenAt,
            String restoredBy,
            Instant restoredAt,
            String restoreNote,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AdminHideReviewRequest(
            @NotBlank @Size(min = 5, max = 1000) String reason) {
    }

    public record AdminRestoreReviewRequest(
            @Size(max = 1000) String note) {
    }

    public record AdminResyncRatingRequest(
            UUID providerId) {
    }

    public record ProviderRatingSnapshot(
            UUID providerId,
            double ratingAvg,
            int reviewCount,
            Instant calculatedAt) {
    }

    public record AdminResyncRatingResponse(
            int resyncedProviders,
            List<ProviderRatingSnapshot> snapshots) {
    }
}
