package com.homecare.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class OrgRolePermissionMapperTest {

    @Test
    void mapsOrgRolesExplicitlyWithoutWildcardsOrOrderReadEscalation() {
        Set<String> ownerPerms = OrgRolePermissionMapper.permissionsFor("ORG_OWNER");
        assertThat(ownerPerms).containsExactlyInAnyOrder(
                "service:manage",
                "technician:manage",
                "schedule:manage",
                "booking:read",
                "booking:dispatch",
                "product:manage",
                "order:read",
                "order:fulfill",
                "order:return_resolve",
                "warranty:read",
                "organization:read",
                "organization:manage");
        assertThat(ownerPerms).doesNotContain(RbacConstants.PERM_ADMIN_ALL, RbacConstants.ROLE_ORGANIZATION);

        assertThat(OrgRolePermissionMapper.permissionsFor("ORG_ADMIN")).isEqualTo(ownerPerms);

        assertThat(OrgRolePermissionMapper.permissionsFor("ORG_STAFF")).containsExactlyInAnyOrder(
                "booking:read",
                "booking:dispatch",
                "order:read",
                "order:fulfill",
                "order:return_resolve",
                "warranty:read");

        assertThat(OrgRolePermissionMapper.permissionsFor("ORG_TECHNICIAN")).isEmpty();
        assertThat(OrgRolePermissionMapper.permissionsFor("UNKNOWN")).isEmpty();
        assertThat(OrgRolePermissionMapper.permissionsFor(null)).isEmpty();
    }
}
