package com.homecare.provider.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public final class ProviderDtos {

    private ProviderDtos() {
    }

    public record ServiceOfferingResponse(
            UUID id,
            UUID providerId,
            String providerName,
            String name,
            String category,
            String description,
            long basePrice,
            int estimatedDurationMinutes,
            boolean active) {
    }

    public record ProviderResponse(
            UUID id,
            String name,
            String description,
            String city,
            String phone,
            double ratingAvg,
            String status) {
    }

    public record TechnicianResponse(
            UUID id,
            String profileId,
            UUID providerId,
            String fullName,
            List<String> specialties,
            int experienceYears,
            double ratingAvg,
            int activeAllocations,
            String status) {
    }

    public record ScheduleWindowResponse(
            UUID id,
            UUID technicianId,
            Instant startsAt,
            Instant endsAt,
            String status,
            UUID allocatedBookingId,
            Long version) {
    }

    public record CreateScheduleWindowRequest(
            @NotNull Instant startsAt,
            @NotNull Instant endsAt) {
    }

    public record UpdateScheduleWindowRequest(
            Instant startsAt,
            Instant endsAt,
            String status) {
    }
}
