package com.homecare.provider.dto;

import com.homecare.provider.entity.ServicePriceType;
import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProviderDtos {

    public record OfferingInput(UUID providerId,
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String name,
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String category,
            @Size(max = 1000) String description,
            @PositiveOrZero Long basePrice,
            @Positive Integer estimatedDurationMinutes,
            Boolean active,
            ServicePriceType priceType,
            @PositiveOrZero Long inspectionFee) {
        public OfferingInput(UUID providerId,
                String name,
                String category,
                String description,
                Long basePrice,
                Integer estimatedDurationMinutes,
                Boolean active) {
            this(providerId, name, category, description, basePrice, estimatedDurationMinutes, active, null, null);
        }
    }

    public record CoverageAreaItem(
            @NotBlank String locationCode,
            @Pattern(regexp = "PROVINCE|WARD") @NotBlank String level,
            Boolean active) {
        public CoverageAreaItem(String locationCode, String level) {
            this(locationCode, level, true);
        }
    }

    public record UpsertServiceCoverageRequest(
            @NotNull List<CoverageAreaItem> areas) {
    }

    public record TechnicianInput(UUID providerId,
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String profileId,
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String fullName,
            @Size(min = 1) List<@NotBlank String> specialties,
            @PositiveOrZero Integer experienceYears,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status) {}

    public record ProviderInput(
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String name,
            @Size(max = 1000) String description,
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String city,
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String phone) {}

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
            boolean active,
            ServicePriceType priceType,
            Long inspectionFee,
            int priceVersion,
            List<CoverageAreaItem> coverageAreas) {
        public ServiceOfferingResponse(
                UUID id,
                UUID providerId,
                String providerName,
                String name,
                String category,
                String description,
                long basePrice,
                int estimatedDurationMinutes,
                boolean active) {
            this(id, providerId, providerName, name, category, description, basePrice, estimatedDurationMinutes, active,
                    ServicePriceType.FIXED, null, 1, List.of());
        }
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
