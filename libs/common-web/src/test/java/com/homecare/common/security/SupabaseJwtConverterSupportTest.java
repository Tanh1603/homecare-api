package com.homecare.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SupabaseJwtConverterSupportTest {

    @Test
    void extractsRolesAndPermissionsFromCustomClaims() {
        var claims = new java.util.HashMap<String, Object>();
        claims.put("sub", "user-uuid-12345");
        claims.put("email", "customer@homecare.com");
        claims.put("user_roles", List.of("CUSTOMER"));
        claims.put("permissions", List.of("booking:create", "device:read", "order:create"));

        Jwt jwt = new Jwt(
                "mock-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                claims);

        Set<String> roles = SupabaseJwtConverterSupport.extractRoles(jwt);
        assertThat(roles).containsExactly("CUSTOMER");
        assertThat(SupabaseJwtConverterSupport.hasAnyRole(jwt, "CUSTOMER")).isTrue();
        assertThat(SupabaseJwtConverterSupport.hasAnyRole(jwt, "ADMIN")).isFalse();

        Set<String> permissions = SupabaseJwtConverterSupport.extractPermissions(jwt);
        assertThat(permissions).containsExactlyInAnyOrder("booking:create", "device:read", "order:create");
        assertThat(SupabaseJwtConverterSupport.hasAnyPermission(jwt, "booking:create")).isTrue();
        assertThat(SupabaseJwtConverterSupport.hasAnyPermission(jwt, "admin:all")).isFalse();

        List<GrantedAuthority> authorities = SupabaseJwtConverterSupport.extractAuthorities(jwt);
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        "ROLE_CUSTOMER",
                        "booking:create",
                        "device:read",
                        "order:create");
    }

    @Test
    void extractsRolesAndPermissionsFromAppMetadata() {
        Map<String, Object> appMetadata = Map.of(
                "user_roles", List.of("TECHNICIAN", "ADMIN"),
                "permissions", List.of("technician:manage", "booking:dispatch"));

        var claims = new java.util.HashMap<String, Object>();
        claims.put("sub", "user-uuid-67890");
        claims.put("app_metadata", appMetadata);

        Jwt jwt = new Jwt(
                "mock-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                claims);

        Set<String> roles = SupabaseJwtConverterSupport.extractRoles(jwt);
        assertThat(roles).containsExactlyInAnyOrder("TECHNICIAN", "ADMIN");
        assertThat(SupabaseJwtConverterSupport.hasAnyRole(jwt, "ADMIN")).isTrue();
        assertThat(SupabaseJwtConverterSupport.hasAnyRole(jwt, "CUSTOMER")).isFalse();

        Set<String> permissions = SupabaseJwtConverterSupport.extractPermissions(jwt);
        assertThat(permissions).containsExactlyInAnyOrder("technician:manage", "booking:dispatch");
        assertThat(SupabaseJwtConverterSupport.hasAnyPermission(jwt, "technician:manage")).isTrue();
    }

    @Test
    void handlesNullAndEmptyTokensSafely() {
        assertThat(SupabaseJwtConverterSupport.extractRoles(null)).isEmpty();
        assertThat(SupabaseJwtConverterSupport.extractPermissions(null)).isEmpty();
        assertThat(SupabaseJwtConverterSupport.extractAuthorities(null)).isEmpty();
        assertThat(SupabaseJwtConverterSupport.hasAnyRole(null, "ADMIN")).isFalse();
        assertThat(SupabaseJwtConverterSupport.hasAnyPermission(null, "booking:create")).isFalse();

        Jwt emptyJwt = new Jwt(
                "empty-token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", "empty-user"));
        assertThat(SupabaseJwtConverterSupport.extractRoles(emptyJwt)).isEmpty();
        assertThat(SupabaseJwtConverterSupport.extractPermissions(emptyJwt)).isEmpty();
        assertThat(SupabaseJwtConverterSupport.extractAuthorities(emptyJwt)).isEmpty();
        assertThat(SupabaseJwtConverterSupport.hasAnyRole(emptyJwt, "ADMIN")).isFalse();
        assertThat(SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                emptyJwt, new String[]{RbacConstants.ROLE_ADMIN}, RbacConstants.PERM_ORGANIZATION_REVIEW)).isFalse();
    }

    @Test
    void hasAnyRoleOrPermissionMatchesEitherRoleOrPermission() {
        Jwt permOnlyJwt = new Jwt(
                "perm-only",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", "u1", "permissions", List.of(RbacConstants.PERM_ORGANIZATION_REVIEW)));

        assertThat(SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                permOnlyJwt, new String[]{RbacConstants.ROLE_ADMIN}, RbacConstants.PERM_ORGANIZATION_REVIEW)).isTrue();
        assertThat(SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                permOnlyJwt, new String[]{RbacConstants.ROLE_ADMIN}, RbacConstants.PERM_ORDER_FULFILL)).isFalse();
    }
}
