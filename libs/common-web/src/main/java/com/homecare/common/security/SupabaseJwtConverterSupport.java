package com.homecare.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Supabase JWT converter extracting roles (ROLE_*) and granular permissions (direct authorities).
 */
public final class SupabaseJwtConverterSupport {

    private SupabaseJwtConverterSupport() {
    }

    public static JwtAuthenticationConverter createJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            if (isUserStatusBlocked(jwt)) {
                throw new InvalidBearerTokenException("User account is suspended or banned.");
            }
            return extractAuthorities(jwt);
        });
        return converter;
    }

    public static boolean isUserStatusBlocked(Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        if (isBlockedStatusValue(jwt.getClaimAsString("user_status"))) {
            return true;
        }
        Object appMetadata = jwt.getClaim("app_metadata");
        if (appMetadata instanceof Map<?, ?> map && map.get("user_status") != null) {
            return isBlockedStatusValue(map.get("user_status").toString());
        }
        return false;
    }

    private static boolean isBlockedStatusValue(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toUpperCase();
        return "SUSPENDED".equals(normalized)
                || "BANNED".equals(normalized)
                || "DISABLED".equals(normalized)
                || "INACTIVE".equals(normalized);
    }

    public static List<GrantedAuthority> extractAuthorities(Jwt jwt) {
        if (jwt == null || isUserStatusBlocked(jwt)) {
            return List.of();
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : extractRoles(jwt)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        for (String permission : extractPermissions(jwt)) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        return authorities;
    }

    public static Set<String> extractRoles(Jwt jwt) {
        if (jwt == null || isUserStatusBlocked(jwt)) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        // 1. Supabase custom claims (user_roles or roles)
        extractStringsFromClaim(jwt.getClaim("user_roles"), roles);
        extractStringsFromClaim(jwt.getClaim("roles"), roles);

        // 2. Supabase app_metadata
        Object appMetadata = jwt.getClaim("app_metadata");
        if (appMetadata instanceof Map<?, ?> map) {
            extractStringsFromClaim(map.get("user_roles"), roles);
            extractStringsFromClaim(map.get("roles"), roles);
        }

        return roles;
    }

    public static Set<String> extractPermissions(Jwt jwt) {
        if (jwt == null || isUserStatusBlocked(jwt)) {
            return Set.of();
        }
        Set<String> permissions = new LinkedHashSet<>();
        // 1. Supabase custom claims: permissions
        extractStringsFromClaim(jwt.getClaim("permissions"), permissions);

        // 2. Supabase app_metadata: permissions
        Object appMetadata = jwt.getClaim("app_metadata");
        if (appMetadata instanceof Map<?, ?> map) {
            extractStringsFromClaim(map.get("permissions"), permissions);
        }

        return permissions;
    }

    public static boolean hasAnyRole(Jwt jwt, String... targetRoles) {
        if (jwt == null || targetRoles == null || targetRoles.length == 0) {
            return false;
        }
        Set<String> userRoles = extractRoles(jwt);
        for (String target : targetRoles) {
            if (userRoles.contains(target) || userRoles.contains(target.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAnyPermission(Jwt jwt, String... targetPermissions) {
        if (jwt == null || targetPermissions == null || targetPermissions.length == 0) {
            return false;
        }
        Set<String> userPermissions = extractPermissions(jwt);
        for (String target : targetPermissions) {
            if (userPermissions.contains(target)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAnyRoleOrPermission(Jwt jwt, String[] targetRoles, String... targetPermissions) {
        return hasAnyRole(jwt, targetRoles) || hasAnyPermission(jwt, targetPermissions);
    }

    public static String extractDisplayName(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        Object userMeta = jwt.getClaim("user_metadata");
        if (userMeta instanceof Map<?, ?> map) {
            for (String key : List.of("full_name", "name")) {
                Object val = map.get(key);
                if (val != null && !val.toString().isBlank()) {
                    return val.toString().trim();
                }
            }
        }
        for (String claim : List.of("name", "email")) {
            String val = jwt.getClaimAsString(claim);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        return null;
    }

    private static void extractStringsFromClaim(Object claim, Set<String> accumulator) {
        if (claim instanceof Collection<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    accumulator.add(item.toString().trim());
                }
            }
        } else if (claim instanceof String str && !str.isBlank()) {
            accumulator.add(str.trim());
        }
    }
}
