package com.homecare.common.security;

import java.util.Map;
import java.util.Set;

/**
 * Authoritative, explicit mapping from verified organization membership roles to organization-scoped permissions.
 */
public final class OrgRolePermissionMapper {

    private static final Set<String> ORG_MANAGER_PERMISSIONS = Set.of(
            RbacConstants.PERM_SERVICE_MANAGE,
            RbacConstants.PERM_TECHNICIAN_MANAGE,
            RbacConstants.PERM_SCHEDULE_MANAGE,
            RbacConstants.PERM_BOOKING_READ,
            RbacConstants.PERM_BOOKING_DISPATCH,
            RbacConstants.PERM_PRODUCT_MANAGE,
            RbacConstants.PERM_ORDER_READ,
            RbacConstants.PERM_ORDER_FULFILL,
            RbacConstants.PERM_ORDER_RETURN_RESOLVE,
            RbacConstants.PERM_WARRANTY_READ,
            RbacConstants.PERM_ORGANIZATION_READ,
            RbacConstants.PERM_ORGANIZATION_MANAGE);

    private static final Set<String> ORG_STAFF_PERMISSIONS = Set.of(
            RbacConstants.PERM_BOOKING_READ,
            RbacConstants.PERM_BOOKING_DISPATCH,
            RbacConstants.PERM_ORDER_READ,
            RbacConstants.PERM_ORDER_FULFILL,
            RbacConstants.PERM_ORDER_RETURN_RESOLVE,
            RbacConstants.PERM_WARRANTY_READ);

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            RbacConstants.ORG_ROLE_OWNER, ORG_MANAGER_PERMISSIONS,
            RbacConstants.ORG_ROLE_ADMIN, ORG_MANAGER_PERMISSIONS,
            RbacConstants.ORG_ROLE_STAFF, ORG_STAFF_PERMISSIONS,
            RbacConstants.ORG_ROLE_TECHNICIAN, Set.of());

    private OrgRolePermissionMapper() {
    }

    public static Set<String> permissionsFor(String orgRole) {
        if (orgRole == null || orgRole.isBlank()) {
            return Set.of();
        }
        return ROLE_PERMISSIONS.getOrDefault(orgRole.trim().toUpperCase(), Set.of());
    }
}
