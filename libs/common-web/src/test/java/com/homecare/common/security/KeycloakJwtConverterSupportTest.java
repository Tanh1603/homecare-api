package com.homecare.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtConverterSupportTest {

    @Test
    void extractsRolesAndAuthoritiesFromFlatRealmAccess() {
        Jwt jwt = createJwt(
                Map.of("roles", List.of("CUSTOMER", "TECHNICIAN")),
                null);

        Set<String> roles = KeycloakJwtConverterSupport.extractRoles(jwt);
        assertThat(roles).containsExactlyInAnyOrder("CUSTOMER", "TECHNICIAN");

        List<GrantedAuthority> authorities = KeycloakJwtConverterSupport.extractAuthorities(jwt);
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_TECHNICIAN");

        assertThat(KeycloakJwtConverterSupport.hasAnyRole(jwt, "CUSTOMER")).isTrue();
        assertThat(KeycloakJwtConverterSupport.hasAnyRole(jwt, "ADMIN")).isFalse();
    }

    @Test
    void extractsRolesFromNestedRealmAccessMapStructure() {
        // User's specific Keycloak case: nested under "account"
        Map<String, Object> nestedRealmAccess = Map.of(
                "account", Map.of(
                        "roles", List.of("manage-account", "manage-account-links", "view-profile")));

        Jwt jwt = createJwt(nestedRealmAccess, null);

        Set<String> roles = KeycloakJwtConverterSupport.extractRoles(jwt);
        assertThat(roles).containsExactlyInAnyOrder("manage-account", "manage-account-links", "view-profile");

        assertThat(KeycloakJwtConverterSupport.hasAnyRole(jwt, "view-profile")).isTrue();
        assertThat(KeycloakJwtConverterSupport.hasAnyRole(jwt, "ADMIN")).isFalse();

        List<GrantedAuthority> authorities = KeycloakJwtConverterSupport.extractAuthorities(jwt);
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_manage-account", "ROLE_manage-account-links", "ROLE_view-profile");
    }

    @Test
    void extractsRolesFromKeycloakStandardResourceAccessClientRoles() {
        Map<String, Object> resourceAccess = Map.of(
                "account", Map.of("roles", List.of("view-profile")),
                "homecare-client", Map.of("roles", List.of("TECHNICIAN", "ADMIN")));

        Jwt jwt = createJwt(null, resourceAccess);

        Set<String> roles = KeycloakJwtConverterSupport.extractRoles(jwt);
        assertThat(roles).containsExactlyInAnyOrder("view-profile", "TECHNICIAN", "ADMIN");

        assertThat(KeycloakJwtConverterSupport.hasAnyRole(jwt, "ADMIN")).isTrue();
        assertThat(KeycloakJwtConverterSupport.hasAnyRole(jwt, "TECHNICIAN")).isTrue();
        assertThat(KeycloakJwtConverterSupport.hasAnyRole(jwt, "CUSTOMER")).isFalse();
    }

    @Test
    void mergesBothRealmAccessAndResourceAccessRolesWithoutDuplicates() {
        Map<String, Object> realmAccess = Map.of("roles", List.of("CUSTOMER", "ADMIN"));
        Map<String, Object> resourceAccess = Map.of(
                "account", Map.of("roles", List.of("view-profile")),
                "backend-api", Map.of("roles", List.of("ADMIN", "TECHNICIAN")));

        Jwt jwt = createJwt(realmAccess, resourceAccess);

        Set<String> roles = KeycloakJwtConverterSupport.extractRoles(jwt);
        assertThat(roles).containsExactlyInAnyOrder("CUSTOMER", "ADMIN", "view-profile", "TECHNICIAN");

        List<GrantedAuthority> authorities = KeycloakJwtConverterSupport.extractAuthorities(jwt);
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_ADMIN", "ROLE_view-profile", "ROLE_TECHNICIAN");
    }

    @Test
    void handlesNullAndEmptyTokensSafely() {
        assertThat(KeycloakJwtConverterSupport.extractRoles(null)).isEmpty();
        assertThat(KeycloakJwtConverterSupport.extractAuthorities(null)).isEmpty();
        assertThat(KeycloakJwtConverterSupport.hasAnyRole(null, "ADMIN")).isFalse();

        Jwt emptyJwt = createJwt(null, null);
        assertThat(KeycloakJwtConverterSupport.extractRoles(emptyJwt)).isEmpty();
        assertThat(KeycloakJwtConverterSupport.extractAuthorities(emptyJwt)).isEmpty();
        assertThat(KeycloakJwtConverterSupport.hasAnyRole(emptyJwt, "ADMIN")).isFalse();
    }

    private Jwt createJwt(Map<String, Object> realmAccess, Map<String, Object> resourceAccess) {
        var claims = new java.util.HashMap<String, Object>();
        claims.put("sub", "user-uuid-12345");
        if (realmAccess != null) {
            claims.put("realm_access", realmAccess);
        }
        if (resourceAccess != null) {
            claims.put("resource_access", resourceAccess);
        }
        return new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                claims);
    }
}
