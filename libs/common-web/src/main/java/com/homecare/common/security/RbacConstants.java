package com.homecare.common.security;

/**
 * Centralized RBAC role and permission constants matching Supabase RBAC master data.
 */
public final class RbacConstants {

    private RbacConstants() {
    }

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_ORGANIZATION = "ORGANIZATION";
    public static final String ROLE_TECHNICIAN = "TECHNICIAN";
    public static final String ROLE_CUSTOMER = "CUSTOMER";

    public static final String PERM_ADMIN_ALL = "admin:all";
    public static final String PERM_ORGANIZATION_READ = "organization:read";
    public static final String PERM_ORGANIZATION_MANAGE = "organization:manage";
    public static final String PERM_ORGANIZATION_REVIEW = "organization:review";

    public static final String PERM_SCHEDULE_MANAGE = "schedule:manage";
    public static final String PERM_BOOKING_READ = "booking:read";
    public static final String PERM_BOOKING_DISPATCH = "booking:dispatch";
    public static final String PERM_BOOKING_UPDATE_STATUS = "booking:update_status";
    public static final String PERM_ORDER_FULFILL = "order:fulfill";
    public static final String PERM_ORDER_RETURN_RESOLVE = "order:return_resolve";

    public static final String HEADER_ORGANIZATION_ID = "X-Organization-Id";

    public static final String EXPR_ORG_REVIEW =
            "hasAnyAuthority('" + PERM_ORGANIZATION_REVIEW + "', '" + PERM_ADMIN_ALL + "') or hasRole('" + ROLE_ADMIN + "')";
}
