package com.homecare.review.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record CreateServiceReviewRequest(
            @NotNull UUID bookingId,
            UUID providerId,
            @Min(1) @Max(5) int rating,
            @NotBlank String comment) {
    }

    public record CreateProductReviewRequest(
            @NotNull UUID orderLineId,
            @Min(1) @Max(5) int rating,
            @NotBlank String comment) {
    }

    public record UpdateReviewRequest(
            @Min(1) @Max(5) Integer rating,
            String comment) {
    }

    public record ReviewResponse(
            UUID id,
            String authorProfileId,
            String targetType,
            UUID sourceId,
            UUID targetId,
            int rating,
            String comment,
            String status,
            Instant createdAt,
            Instant updatedAt) {
    }
}
