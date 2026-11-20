package com.homecare.household.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

public final class AddressDtos {

    private AddressDtos() {
    }

    public record LocationResponse(
            String officialCode,
            String name,
            String level,
            String codename,
            String divisionType,
            Integer phoneCode,
            String parentCode) {
    }

    public record CreateUserAddressRequest(
            @NotBlank String label,
            @NotBlank String recipientName,
            @NotBlank String phoneNumber,
            @NotBlank String addressLine,
            @NotBlank String locationCode,
            Double latitude,
            Double longitude,
            Boolean defaultShipping) {
    }

    @Getter
    @Setter
    public static class UpdateUserAddressRequest {
        private String label;
        private String recipientName;
        private String phoneNumber;
        private String addressLine;
        private String locationCode;
        private Double latitude;
        private Double longitude;
        private boolean latitudeProvided;
        private boolean longitudeProvided;

        @JsonCreator
        public UpdateUserAddressRequest() {
        }

        public UpdateUserAddressRequest(
                String label,
                String recipientName,
                String phoneNumber,
                String addressLine,
                String locationCode,
                Double latitude,
                Double longitude) {
            this.label = label;
            this.recipientName = recipientName;
            this.phoneNumber = phoneNumber;
            this.addressLine = addressLine;
            this.locationCode = locationCode;
            if (latitude != null) {
                setLatitude(latitude);
            }
            if (longitude != null) {
                setLongitude(longitude);
            }
        }

        @JsonSetter(value = "latitude", nulls = Nulls.SET)
        public void setLatitude(Double latitude) {
            this.latitude = latitude;
            this.latitudeProvided = true;
        }

        @JsonSetter(value = "longitude", nulls = Nulls.SET)
        public void setLongitude(Double longitude) {
            this.longitude = longitude;
            this.longitudeProvided = true;
        }
    }

    public record UserAddressResponse(
            UUID id,
            String label,
            String recipientName,
            String phoneNumber,
            String addressLine,
            String wardCode,
            String wardName,
            String provinceCode,
            String provinceName,
            String formattedAddress,
            Double latitude,
            Double longitude,
            boolean defaultShipping,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AssignHouseholdServiceAddressRequest(
            @NotNull UUID addressId) {
    }
}
