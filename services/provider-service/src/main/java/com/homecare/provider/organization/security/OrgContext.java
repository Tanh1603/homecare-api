package com.homecare.provider.organization.security;

import java.util.UUID;

import com.homecare.provider.organization.entity.OrgRole;

public record OrgContext(UUID organizationId, String userId, OrgRole role) {

    private static final ThreadLocal<OrgContext> CURRENT = new ThreadLocal<>();

    public static void set(OrgContext context) {
        CURRENT.set(context);
    }

    public static OrgContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
