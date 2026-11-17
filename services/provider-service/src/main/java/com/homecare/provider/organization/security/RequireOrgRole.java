package com.homecare.provider.organization.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.homecare.provider.organization.entity.OrgRole;

@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireOrgRole {
    OrgRole[] value() default {
            OrgRole.ORG_OWNER,
            OrgRole.ORG_ADMIN,
            OrgRole.ORG_STAFF,
            OrgRole.ORG_TECHNICIAN
    };
}
