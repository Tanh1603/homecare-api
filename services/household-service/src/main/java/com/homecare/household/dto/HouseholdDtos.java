package com.homecare.household.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class HouseholdDtos {

    private HouseholdDtos() {
    }

    public record CreateHouseholdRequest(
            @NotBlank String name,
            @NotBlank String address,
            String timezone) {
    }

    public record UpdateHouseholdRequest(
            String name,
            String address,
            String timezone) {
    }

    public record HouseholdResponse(
            UUID id,
            String ownerProfileId,
            String name,
            String address,
            String timezone,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CreateDeviceRequest(
            UUID householdId,
            @NotBlank String name,
            @JsonAlias({"type", "deviceType"}) @NotBlank String deviceType,
            @NotBlank String brand,
            @NotBlank String model,
            String serialNumber,
            LocalDate purchaseDate) {
    }

    public record UpdateDeviceRequest(
            String name,
            @JsonAlias({"type", "deviceType"}) String deviceType,
            String brand,
            String model,
            String serialNumber,
            LocalDate purchaseDate,
            String status) {
    }

    public record DeviceResponse(
            UUID id,
            UUID householdId,
            String ownerProfileId,
            String name,
            String deviceType,
            String type,
            String brand,
            String model,
            String serialNumber,
            LocalDate purchaseDate,
            String status,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CreateWarrantyRequest(
            @NotBlank String providerName,
            @JsonAlias({"startsOn", "startDate"}) @NotNull LocalDate startsOn,
            @JsonAlias({"endsOn", "endDate"}) @NotNull LocalDate endsOn,
            @JsonAlias({"notes", "coverageSummary"}) String notes,
            String documentUrl,
            UUID organizationId) {

        public CreateWarrantyRequest(
                String providerName,
                LocalDate startsOn,
                LocalDate endsOn,
                String notes,
                String documentUrl) {
            this(providerName, startsOn, endsOn, notes, documentUrl, null);
        }
    }

    public record UpdateWarrantyRequest(
            String providerName,
            @JsonAlias({"startsOn", "startDate"}) LocalDate startsOn,
            @JsonAlias({"endsOn", "endDate"}) LocalDate endsOn,
            @JsonAlias({"notes", "coverageSummary"}) String notes,
            String documentUrl,
            String status) {
    }

    public record WarrantyResponse(
            UUID id,
            UUID deviceId,
            UUID organizationId,
            UUID orderId,
            UUID orderLineId,
            UUID productId,
            String source,
            String providerName,
            LocalDate startsOn,
            LocalDate startDate,
            LocalDate endsOn,
            LocalDate endDate,
            String notes,
            String coverageSummary,
            String documentUrl,
            String status,
            Instant createdAt) {
    }

    public record CreateMaintenanceScheduleRequest(
            @NotBlank String title,
            Integer cadenceMonths,
            @NotNull LocalDate dueOn) {
    }

    public record UpdateMaintenanceScheduleRequest(
            String title,
            Integer cadenceMonths,
            LocalDate dueOn,
            String status) {
    }

    public record MaintenanceScheduleResponse(
            UUID id,
            UUID deviceId,
            String title,
            Integer cadenceMonths,
            LocalDate dueOn,
            String status,
            Instant notifiedAt,
            Instant completedAt,
            Instant createdAt) {
    }

    public record CreateCareRecordRequest(
            @JsonAlias({"kind", "careType"}) @NotBlank String kind,
            @JsonAlias({"occurredAt", "performedAt"}) Instant occurredAt,
            String title,
            String notes,
            UUID bookingId) {
    }

    public record UpdateCareRecordRequest(
            @JsonAlias({"kind", "careType"}) String kind,
            @JsonAlias({"occurredAt", "performedAt"}) Instant occurredAt,
            String title,
            String notes) {
    }

    public record CareRecordResponse(
            UUID id,
            UUID deviceId,
            String kind,
            String careType,
            Instant occurredAt,
            Instant performedAt,
            String title,
            String notes,
            UUID bookingId,
            Instant createdAt) {
    }

    public record VerifyDeviceReferenceResponse(
            boolean valid,
            UUID deviceId,
            UUID householdId,
            String deviceType,
            String deviceName,
            String status) {
    }

    public record VerifyWarrantyResponse(
            boolean valid,
            boolean expired,
            UUID warrantyId,
            UUID deviceId,
            String deviceType,
            UUID organizationId,
            String providerName,
            LocalDate endsOn,
            String status) {
    }
}
