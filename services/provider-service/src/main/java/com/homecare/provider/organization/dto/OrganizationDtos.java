package com.homecare.provider.organization.dto;

import java.time.Instant;
import java.util.UUID;

import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.entity.OrganizationType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class OrganizationDtos {

    private OrganizationDtos() {
    }

    public record RegisterOrganizationRequest(
            @NotBlank String name,
            @NotNull OrganizationType type,
            String taxCode,
            @NotBlank String phone,
            @NotBlank String city,
            String address,
            String description) {
    }

    public record OrganizationResponse(
            UUID id,
            String name,
            OrganizationType type,
            OrganizationStatus status,
            String rejectReason,
            String taxCode,
            String phone,
            String city,
            String address,
            String description,
            String ownerUserId,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record MyOrganizationMembershipResponse(
            UUID organizationId,
            String organizationName,
            OrganizationType type,
            OrganizationStatus organizationStatus,
            String rejectReason,
            OrgRole role,
            String memberStatus,
            Instant joinedAt) {
    }

    public record AddMemberRequest(
            @NotBlank String userId,
            @NotNull OrgRole role) {
    }

    public record UpdateMemberRoleRequest(
            @NotNull OrgRole role) {
    }

    public record OrganizationMemberResponse(
            UUID id,
            UUID organizationId,
            String userId,
            OrgRole role,
            String status,
            Instant joinedAt) {
    }

    public record RejectOrganizationRequest(
            @NotBlank String reason) {
    }
}
