package com.homecare.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * Verified organization membership result returned by a trusted membership verifier.
 */
public record VerifiedMembership(
        UUID organizationId,
        String userId,
        String role,
        Set<String> permissions,
        boolean active) {

    public VerifiedMembership {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public static VerifiedMembership of(UUID organizationId, String userId, String role, boolean active) {
        Set<String> mapped = active ? OrgRolePermissionMapper.permissionsFor(role) : Set.of();
        return new VerifiedMembership(organizationId, userId, role, mapped, active);
    }

    public static VerifiedMembership of(
            UUID organizationId, String userId, String role, Set<String> permissions, boolean active) {
        return new VerifiedMembership(organizationId, userId, role, permissions, active);
    }
}
