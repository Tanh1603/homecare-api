package com.homecare.common.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

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
        return extractAuthoritiesFromRealmAccess(jwt.getClaim("realm_access"));
    }

    public static List<GrantedAuthority> extractAuthoritiesFromRealmAccess(Object realmAccessClaim) {
        if (realmAccessClaim instanceof Map<?, ?> m && m.get("roles") instanceof Collection<?> roles) {
            return roles.stream()
                    .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        }
        return List.of();
    }
}
