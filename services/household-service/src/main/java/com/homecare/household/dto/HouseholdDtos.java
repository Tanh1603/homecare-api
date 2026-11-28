package com.homecare.household.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public final class HouseholdDtos {

    private HouseholdDtos() {
    }

    public record CreateHouseholdRequest(
            @NotBlank String name,
            String address,
            String timezone,
            UUID defaultServiceAddressId) {
        public CreateHouseholdRequest(String name, String address, String timezone) {
            this(name, address, timezone, null);
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class UpdateHouseholdRequest {
        private String name;
        private String address;
        private String timezone;
        private UUID defaultServiceAddressId;
        private boolean defaultServiceAddressIdProvided;

        public UpdateHouseholdRequest(String name, String address, String timezone) {
            this.name = name;
            this.address = address;
            this.timezone = timezone;
        }

        @JsonSetter("defaultServiceAddressId")
        public void setDefaultServiceAddressId(UUID defaultServiceAddressId) {
            this.defaultServiceAddressId = defaultServiceAddressId;
            this.defaultServiceAddressIdProvided = true;
        }
    }

    public record HouseholdResponse(
            UUID id,
            String ownerProfileId,
            String name,
            String address,
            String timezone,
            UUID defaultServiceAddressId,
            Instant createdAt,
            Instant updatedAt) {
        public HouseholdResponse(
                UUID id,
                String ownerProfileId,
                String name,
                String address,
                String timezone,
                Instant createdAt,
                Instant updatedAt) {
            this(id, ownerProfileId, name, address, timezone, null, createdAt, updatedAt);
        }
    }

    public record CreateDeviceRequest(
            UUID householdId,
            @NotBlank String name,
            @JsonAlias({"type", "deviceType"}) @NotBlank String deviceType,
            @NotBlank String brand,
            String model,
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

    public record RegisterSerialRequest(@NotBlank String serialNumber) {
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
            String originSource,
            Instant archivedAt,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
        public DeviceResponse(
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
            this(id, householdId, ownerProfileId, name, deviceType, type, brand, model,
                    serialNumber, purchaseDate, status, "MANUAL", null, version, createdAt, updatedAt);
        }
    }

    public record DeviceRemovalResponse(
            String outcome,
            DeviceResponse device) {
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

    public record AttachWarrantyEvidenceRequest(
            @NotBlank String evidenceRef) {
    }

    public record AdminVerifyWarrantyRequest(
            @NotBlank String verificationStatus,
            String note) {
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
            String ownerName,
            LocalDate startsOn,
            LocalDate startDate,
            LocalDate endsOn,
            LocalDate endDate,
            String notes,
            String coverageSummary,
            String documentUrl,
            String status,
            String manufacturerRegistrationStatus,
            String triggerEvidenceRef,
            Instant createdAt,
            String warrantyCategory,
            String warrantyIssuer,
            String verificationStatus,
            String validityStatus,
            String authorizationEvidenceRef,
            String verifiedBy,
            Instant verifiedAt,
            String verificationNote,
            Long version) {
        public WarrantyResponse(UUID id, UUID deviceId, UUID organizationId, UUID orderId, UUID orderLineId,
                UUID productId, String source, String providerName, String ownerName, LocalDate startsOn,
                LocalDate startDate, LocalDate endsOn, LocalDate endDate, String notes, String coverageSummary,
                String documentUrl, String status, String manufacturerRegistrationStatus, String triggerEvidenceRef,
                Instant createdAt) {
            this(id, deviceId, organizationId, orderId, orderLineId, productId, source, providerName, ownerName,
                    startsOn, startDate, endsOn, endDate, notes, coverageSummary, documentUrl, status,
                    manufacturerRegistrationStatus, triggerEvidenceRef, createdAt,
                    "PRODUCT_MANUFACTURER", "MANUFACTURER", "UNVERIFIED", "ACTIVE", null, null, null, null, 0L);
        }

        public WarrantyResponse(UUID id, UUID deviceId, UUID organizationId, UUID orderId, UUID orderLineId,
                UUID productId, String source, String providerName, LocalDate startsOn, LocalDate startDate,
                LocalDate endsOn, LocalDate endDate, String notes, String coverageSummary, String documentUrl,
                String status, Instant createdAt) {
            this(id, deviceId, organizationId, orderId, orderLineId, productId, source, providerName, null,
                    startsOn, startDate, endsOn, endDate, notes, coverageSummary, documentUrl, status,
                    null, null, createdAt);
        }
    }

    public record DeviceLifecycleResponse(
            UUID deviceId,
            UUID householdId,
            String ownerProfileId,
            String name,
            String deviceType,
            String brand,
            String model,
            String serialNumber,
            String installationStatus,
            Instant installationCompletedAt,
            UUID installationBookingId,
            UUID orderId,
            UUID orderLineId,
            String warrantyStatus,
            String warrantySource,
            String manufacturerRegistrationStatus,
            String triggerEvidenceRef,
            List<MaintenanceScheduleResponse> maintenanceSchedule,
            List<CareRecordResponse> careTimeline,
            List<UUID> linkedPartOrders) {
    }

    public record CreateMaintenanceScheduleRequest(
            @NotBlank String title,
            Integer cadenceMonths,
            @NotNull LocalDate dueOn,
            Boolean reminderEnabled) {
        public CreateMaintenanceScheduleRequest(String title, Integer cadenceMonths, LocalDate dueOn) {
            this(title, cadenceMonths, dueOn, true);
        }
    }

    public record UpdateMaintenanceScheduleRequest(
            String title,
            Integer cadenceMonths,
            LocalDate dueOn,
            String status,
            Boolean reminderEnabled) {
        public UpdateMaintenanceScheduleRequest(String title, Integer cadenceMonths, LocalDate dueOn, String status) {
            this(title, cadenceMonths, dueOn, status, null);
        }
    }

    public record MaintenanceScheduleResponse(
            UUID id,
            UUID deviceId,
            String title,
            Integer cadenceMonths,
            LocalDate dueOn,
            String status,
            boolean reminderEnabled,
            Instant notifiedAt,
            Instant completedAt,
            Instant lastServicedAt,
            Instant lastSkippedAt,
            Instant createdAt) {
        public MaintenanceScheduleResponse(
                UUID id,
                UUID deviceId,
                String title,
                Integer cadenceMonths,
                LocalDate dueOn,
                String status,
                Instant notifiedAt,
                Instant completedAt,
                Instant createdAt) {
            this(id, deviceId, title, cadenceMonths, dueOn, status, true, notifiedAt, completedAt, null, null, createdAt);
        }
    }

    public record CreateMaintenancePolicyRequest(
            @NotBlank String category,
            String model,
            @NotBlank String title,
            @NotNull Integer cadenceMonths,
            String status,
            LocalDate firstDueOn) {
    }

    public record UpdateMaintenancePolicyRequest(
            String title,
            Integer cadenceMonths,
            String status,
            LocalDate firstDueOn) {
    }

    public record MaintenancePolicyResponse(
            UUID id,
            String category,
            String model,
            String title,
            Integer cadenceMonths,
            Integer policyVersion,
            String status,
            LocalDate firstDueOn,
            Instant createdAt,
            Instant updatedAt) {
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
            String status,
            String brand,
            String model) {
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
            String status,
            String warrantyCategory,
            String warrantyIssuer,
            String verificationStatus,
            String referenceSerial) {
        public VerifyWarrantyResponse(
                boolean valid,
                boolean expired,
                UUID warrantyId,
                UUID deviceId,
                String deviceType,
                UUID organizationId,
                String providerName,
                LocalDate endsOn,
                String status) {
            this(valid, expired, warrantyId, deviceId, deviceType, organizationId, providerName, endsOn, status,
                    "PRODUCT_MANUFACTURER", "MANUFACTURER", "UNVERIFIED", null);
        }
    }
}
