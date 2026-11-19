package com.homecare.provider.organization.dto;

import java.time.Instant;
import java.util.UUID;

import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.entity.OrganizationType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

    public record UpdateOrganizationRequest(
            @Pattern(regexp = ".*\\S.*", message = "name must not be blank") @Size(max = 255) String name,
            @Pattern(regexp = ".*\\S.*", message = "phone must not be blank") @Size(max = 50) String phone,
            @Pattern(regexp = ".*\\S.*", message = "city must not be blank") @Size(max = 120) String city,
            @Size(max = 500) String address,
            @Size(max = 1500) String description) {
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
            String fullName,
            String email,
            OrgRole role,
            String status,
            Instant joinedAt) {
        public OrganizationMemberResponse(UUID id, UUID organizationId, String userId, OrgRole role, String status, Instant joinedAt) {
            this(id, organizationId, userId, null, null, role, status, joinedAt);
        }
    }

    public record RejectOrganizationRequest(
            @NotBlank String reason) {
    }

    public record AdminUserResponse(
            String id,
            String fullName,
            String email,
            String role,
            String status,
            Instant createdAt) {
    }
}
