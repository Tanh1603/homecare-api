package com.homecare.common.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

public final class KeycloakJwtConverterSupport {

    private KeycloakJwtConverterSupport() {
    }

    public static JwtAuthenticationConverter createJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(KeycloakJwtConverterSupport::extractAuthorities);
        return converter;
    }

    public static List<GrantedAuthority> extractAuthorities(Jwt jwt) {
        if (jwt == null) {
            return List.of();
        }
        return extractRoles(jwt).stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    public static Set<String> extractRoles(Jwt jwt) {
        if (jwt == null) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        extractRolesFromClaim(jwt.getClaim("realm_access"), roles);
        extractRolesFromClaim(jwt.getClaim("resource_access"), roles);
        return roles;
    }

    public static boolean hasAnyRole(Jwt jwt, String... targetRoles) {
        if (jwt == null || targetRoles == null || targetRoles.length == 0) {
            return false;
        }
        Set<String> userRoles = extractRoles(jwt);
        for (String target : targetRoles) {
            if (userRoles.contains(target)) {
                return true;
            }
        }
        return false;
    }

    public static List<GrantedAuthority> extractAuthoritiesFromRealmAccess(Object realmAccessClaim) {
        Set<String> roles = new LinkedHashSet<>();
        extractRolesFromClaim(realmAccessClaim, roles);
        return roles.stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    private static void extractRolesFromClaim(Object claim, Set<String> accumulator) {
        if (claim instanceof Map<?, ?> map) {
            if (map.get("roles") instanceof Collection<?> roles) {
                for (Object r : roles) {
                    if (r != null) {
                        accumulator.add(r.toString());
                    }
                }
            }
            for (Object value : map.values()) {
                if (value instanceof Map<?, ?>) {
                    extractRolesFromClaim(value, accumulator);
                }
            }
        }
    }
}
