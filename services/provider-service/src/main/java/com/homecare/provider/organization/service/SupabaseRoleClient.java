package com.homecare.provider.organization.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
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
        this.restClient = RestClient.builder().baseUrl(supabaseUrl).build();
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

    private boolean isConfigured() {
        return supabaseUrl != null && !supabaseUrl.contains("placeholder.supabase.co")
                && secretKey != null && !secretKey.isBlank()
                && !secretKey.equals("placeholder-secret-key")
                && !secretKey.startsWith("your-");
    }
}
