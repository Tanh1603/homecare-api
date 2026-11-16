package com.homecare.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable authenticated actor context separating global RBAC claims from verified organization-scoped roles and permissions.
 */
public record ActorContext(
        String userId,
        AccessMode mode,
        UUID organizationId,
        Set<String> globalRoles,
        Set<String> globalPermissions,
        Set<String> organizationRoles,
        Set<String> organizationPermissions) {

    public ActorContext {
        globalRoles = globalRoles == null ? Set.of() : Set.copyOf(globalRoles);
        globalPermissions = globalPermissions == null ? Set.of() : Set.copyOf(globalPermissions);
        organizationRoles = organizationRoles == null ? Set.of() : Set.copyOf(organizationRoles);
        organizationPermissions = organizationPermissions == null ? Set.of() : Set.copyOf(organizationPermissions);
    }

    public boolean hasGlobalRole(String role) {
        return role != null && globalRoles.contains(role.trim().toUpperCase());
    }

    public boolean hasGlobalPermission(String permission) {
        return permission != null && globalPermissions.contains(permission);
    }

    public boolean hasOrgRole(String... roles) {
        if (roles == null) {
            return false;
        }
        for (String role : roles) {
            if (role != null && organizationRoles.contains(role.trim().toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasOrgPermission(String permission) {
        return permission != null && organizationPermissions.contains(permission);
    }

    public boolean isGlobalAdmin() {
        return hasGlobalRole(RbacConstants.ROLE_ADMIN) || hasGlobalPermission(RbacConstants.PERM_ADMIN_ALL);
    }
}
