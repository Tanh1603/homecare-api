package com.homecare.provider.organization.service;

import static com.homecare.common.error.CommonErrorCode.INTERNAL_ERROR;
import static com.homecare.common.error.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.homecare.common.error.CommonErrorCode.VALIDATION_FAILED;

import com.homecare.common.error.DomainException;
import com.homecare.provider.organization.dto.OrganizationDtos.AdminUserResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException.NotFound;
import org.springframework.web.client.RestClient;

@Service
public class SupabaseRoleClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseRoleClient.class);

    private final String supabaseUrl;
    private final String secretKey;
    private final String organizationRoleId;
    private final RestClient restClient;

    public SupabaseRoleClient(
            @Value("${supabase.url:https://placeholder.supabase.co}") String supabaseUrl,
            @Value("${supabase.secret-key:placeholder-secret-key}") String secretKey,
            @Value("${supabase.organization-role-id:00000000-0000-0000-0001-000000000002}") String organizationRoleId) {
        this.supabaseUrl = supabaseUrl;
        this.secretKey = secretKey;
        this.organizationRoleId = organizationRoleId;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().baseUrl(supabaseUrl).requestFactory(requestFactory).build();
    }

    public void grantOrganizationRole(String userId) {
        if (!isConfigured()) {
            log.info("[AUDIT-ROLE-SYNC] Supabase secret key not configured; skipping remote grant of ORGANIZATION role for userId={}", userId);
            return;
        }
        restClient.post()
                .uri("/rest/v1/user_roles")
                .header("apikey", secretKey)
                .header("Authorization", "Bearer " + secretKey)
                .header("Prefer", "resolution=ignore-duplicates")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("user_id", userId, "role_id", organizationRoleId))
                .retrieve()
                .toBodilessEntity();
        log.info("[AUDIT-ROLE-SYNC] Granted global ORGANIZATION role in Supabase public.user_roles for userId={}", userId);
    }

    public void revokeOrganizationRole(String userId) {
        if (!isConfigured()) {
            log.info("[AUDIT-ROLE-SYNC] Supabase secret key not configured; skipping remote revoke of ORGANIZATION role for userId={}", userId);
            return;
        }
        restClient.delete()
                .uri(uriBuilder -> uriBuilder
                        .path("/rest/v1/user_roles")
                        .queryParam("user_id", "eq." + userId)
                        .queryParam("role_id", "eq." + organizationRoleId)
                        .build())
                .header("apikey", secretKey)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .toBodilessEntity();
        log.info("[AUDIT-ROLE-SYNC] Revoked global ORGANIZATION role in Supabase public.user_roles for userId={}", userId);
    }

    public List<AdminUserResponse> listUsers() {
        if (!isConfigured()) {
            log.warn("[ADMIN-USERS] Supabase secret key not configured; returning empty user list.");
            return List.of();
        }
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/auth/v1/admin/users?page=1&per_page=100")
                    .header("apikey", secretKey)
                    .header("Authorization", "Bearer " + secretKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (body == null || !(body.get("users") instanceof Collection<?> rawUsers)) {
                return List.of();
            }
            List<AdminUserResponse> result = new ArrayList<>();
            for (Object entry : rawUsers) {
                if (!(entry instanceof Map<?, ?> user)) {
                    continue;
                }
                String id = asString(user.get("id"));
                String email = asString(user.get("email"));
                Map<?, ?> userMeta = user.get("user_metadata") instanceof Map<?, ?> m ? m : Map.of();
                Map<?, ?> appMeta = user.get("app_metadata") instanceof Map<?, ?> m ? m : Map.of();

                String fullName = firstNonBlank(
                        asString(userMeta.get("full_name")),
                        asString(userMeta.get("name")),
                        email != null && email.contains("@") ? email.substring(0, email.indexOf('@')) : id);
                String role = resolveHighestRole(appMeta, userMeta);
                String status = isUserSuspended(user, appMeta) ? "SUSPENDED" : "ACTIVE";
                Instant createdAt = parseInstant(asString(user.get("created_at")));
                result.add(new AdminUserResponse(id, fullName, email != null ? email : "", role, status, createdAt));
            }
            return result;
        } catch (Exception ex) {
            log.warn("[ADMIN-USERS] Failed to query Supabase Auth Admin API: {}", ex.getMessage());
            return List.of();
        }
    }

    public void updateUserStatus(UUID userId, String status) {
        if (!List.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw new DomainException(VALIDATION_FAILED);
        }
        if (!isConfigured()) {
            throw new DomainException(INTERNAL_ERROR,
                    "User administration is not configured.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ban_duration", "SUSPENDED".equals(status) ? "876000h" : "none");
        body.put("app_metadata", Map.of("user_status", status));
        try {
            restClient.put().uri("/auth/v1/admin/users/{id}", userId)
                    .header("apikey", secretKey).header("Authorization", "Bearer " + secretKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (NotFound ex) {
            throw new DomainException(RESOURCE_NOT_FOUND);
        }
    }

    private boolean isUserSuspended(Map<?, ?> user, Map<?, ?> appMeta) {
        String metaStatus = asString(appMeta.get("user_status"));
        if ("SUSPENDED".equalsIgnoreCase(metaStatus) || "BANNED".equalsIgnoreCase(metaStatus)) {
            return true;
        }
        String bannedUntil = asString(user.get("banned_until"));
        if (bannedUntil == null || bannedUntil.isBlank() || "null".equalsIgnoreCase(bannedUntil)) {
            return false;
        }
        try {
            return OffsetDateTime.parse(bannedUntil).toInstant().isAfter(Instant.now());
        } catch (Exception ex) {
            return false;
        }
    }

    private String resolveHighestRole(Map<?, ?> appMeta, Map<?, ?> userMeta) {
        List<String> roles = new ArrayList<>();
        collectStrings(appMeta.get("roles"), roles);
        collectStrings(appMeta.get("user_roles"), roles);
        collectStrings(userMeta.get("role"), roles);
        for (String candidate : List.of("ADMIN", "ORGANIZATION", "TECHNICIAN", "CUSTOMER")) {
            if (roles.stream().anyMatch(r -> r.equalsIgnoreCase(candidate))) {
                return candidate;
            }
        }
        return roles.isEmpty() ? "CUSTOMER" : roles.get(0).toUpperCase();
    }

    private void collectStrings(Object value, List<String> out) {
        if (value instanceof Collection<?> col) {
            for (Object item : col) {
                if (item != null && !item.toString().isBlank()) {
                    out.add(item.toString().trim());
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            out.add(s.trim());
        }
    }

    private String asString(Object val) {
        return val != null ? val.toString() : null;
    }

    private String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return "";
    }

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ex) {
            return Instant.now();
        }
    }

    private boolean isConfigured() {
        return supabaseUrl != null && !supabaseUrl.contains("placeholder.supabase.co")
                && secretKey != null && !secretKey.isBlank()
                && !secretKey.equals("placeholder-secret-key")
                && !secretKey.startsWith("your-");
    }
}
