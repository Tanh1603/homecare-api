package com.homecare.provider.organization.service;

import static com.homecare.common.error.CommonErrorCode.INTERNAL_ERROR;
import static com.homecare.common.error.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.homecare.common.error.CommonErrorCode.VALIDATION_FAILED;

import com.homecare.common.error.DomainException;
import com.homecare.common.web.PageResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.AdminUserResponse;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import com.homecare.provider.config.SupabaseProperties;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public SupabaseRoleClient(SupabaseProperties properties) {
        this(properties.getUrl(), properties.getSecretKey(), properties.getOrganizationRoleId());
    }

    public SupabaseRoleClient(
            String supabaseUrl,
            String secretKey,
            String organizationRoleId) {
        this.supabaseUrl = supabaseUrl;
        this.secretKey = secretKey;
        this.organizationRoleId = organizationRoleId;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
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

    public record ProfileSummary(String fullName, String email) {
    }

    public Map<String, ProfileSummary> fetchProfilesBatch(Collection<String> userIds) {
        if (!isConfigured() || userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        String joined = userIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
        if (joined.isBlank()) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> rows = restClient.get()
                    .uri("/rest/v1/profiles?id=in.({ids})&select=id,full_name", joined)
                    .header("apikey", secretKey)
                    .header("Authorization", "Bearer " + secretKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (rows == null || rows.isEmpty()) {
                return Map.of();
            }
            Map<String, ProfileSummary> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String id = asString(row.get("id"));
                if (id != null) {
                    result.put(id, new ProfileSummary(asString(row.get("full_name")), null));
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("[ORG-MEMBERS] Failed batch profile lookup: {}", ex.getMessage());
            return Map.of();
        }
    }

    public List<AdminUserResponse> listUsers() {
        return listUsers(1, 100).items();
    }

    public PageResponse<AdminUserResponse> listUsers(int page, int size) {
        return listUsers(page, size, null, null, null, null, null, null);
    }

    public PageResponse<AdminUserResponse> listUsers(
            int page,
            int size,
            String search,
            String role,
            String status,
            String sortBy,
            String sortDir,
            String excludeUserId) {
        if (!isConfigured()) {
            log.warn("[ADMIN-USERS] Supabase secret key not configured; returning empty user list.");
            return PageResponse.of(List.of(), page, size, 0L);
        }
        boolean needsFullScan = (search != null && !search.isBlank())
                || (role != null && !role.isBlank())
                || (status != null && !status.isBlank())
                || (sortBy != null && !sortBy.isBlank())
                || (excludeUserId != null && !excludeUserId.isBlank());
        try {
            if (!needsFullScan) {
                return fetchUsersSinglePage(page, size);
            }
            List<AdminUserResponse> allUsers = fetchAllUsersForFiltering();
            return filterSortAndPaginateUsers(
                    allUsers, page, size, search, role, status, sortBy, sortDir, excludeUserId);
        } catch (Exception ex) {
            log.warn("[ADMIN-USERS] Failed to query Supabase Auth Admin API: {}", ex.getMessage());
            return PageResponse.of(List.of(), page, size, 0L);
        }
    }

    private PageResponse<AdminUserResponse> fetchUsersSinglePage(int page, int size) {
        Map<String, Object> body = restClient.get()
                .uri("/auth/v1/admin/users?page={page}&per_page={size}", page, size)
                .header("apikey", secretKey)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (body == null || !(body.get("users") instanceof Collection<?> rawUsers)) {
            return PageResponse.of(List.of(), page, size, 0L);
        }
        List<AdminUserResponse> result = mapRawUsers(rawUsers);
        long total = result.size();
        if (body.get("total") instanceof Number n) {
            total = n.longValue();
        } else if (result.size() == size) {
            total = (long) page * size + 1;
        } else {
            total = (long) (page - 1) * size + result.size();
        }
        return PageResponse.of(result, page, size, total);
    }

    private List<AdminUserResponse> fetchAllUsersForFiltering() {
        // shortcut: Supabase Auth Admin API lacks role/status/name filters; scan up to 10 pages x 1000 users.
        // Upgrade to a synced Postgres admin_users projection when user count exceeds 10,000.
        final int scanPageSize = 1000;
        final int maxScanPages = 10;
        List<AdminUserResponse> all = new ArrayList<>();
        for (int scanPage = 1; scanPage <= maxScanPages; scanPage++) {
            Map<String, Object> body = restClient.get()
                    .uri("/auth/v1/admin/users?page={page}&per_page={size}", scanPage, scanPageSize)
                    .header("apikey", secretKey)
                    .header("Authorization", "Bearer " + secretKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (body == null || !(body.get("users") instanceof Collection<?> rawUsers) || rawUsers.isEmpty()) {
                break;
            }
            List<AdminUserResponse> mapped = mapRawUsers(rawUsers);
            all.addAll(mapped);
            if (mapped.size() < scanPageSize) {
                break;
            }
        }
        return all;
    }

    private List<AdminUserResponse> mapRawUsers(Collection<?> rawUsers) {
        List<Map<?, ?>> userEntries = new ArrayList<>();
        List<String> userIds = new ArrayList<>();
        for (Object entry : rawUsers) {
            if (entry instanceof Map<?, ?> user) {
                userEntries.add(user);
                String id = asString(user.get("id"));
                if (id != null && !id.isBlank()) {
                    userIds.add(id);
                }
            }
        }
        Map<String, List<String>> dbRolesByUser = fetchUserRolesBatch(userIds);
        List<AdminUserResponse> result = new ArrayList<>(userEntries.size());
        for (Map<?, ?> user : userEntries) {
            String id = asString(user.get("id"));
            String email = asString(user.get("email"));
            Map<?, ?> userMeta = user.get("user_metadata") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> appMeta = user.get("app_metadata") instanceof Map<?, ?> m ? m : Map.of();

            String fullName = firstNonBlank(
                    asString(userMeta.get("full_name")),
                    asString(userMeta.get("name")),
                    email != null && email.contains("@") ? email.substring(0, email.indexOf('@')) : id);
            String role = resolveHighestRole(appMeta, userMeta, dbRolesByUser.getOrDefault(id, List.of()));
            String status = isUserSuspended(user, appMeta) ? "SUSPENDED" : "ACTIVE";
            Instant createdAt = parseInstant(asString(user.get("created_at")));
            result.add(new AdminUserResponse(id, fullName, email != null ? email : "", role, status, createdAt));
        }
        return result;
    }

    static PageResponse<AdminUserResponse> filterSortAndPaginateUsers(
            List<AdminUserResponse> allUsers,
            int page,
            int size,
            String search,
            String role,
            String status,
            String sortBy,
            String sortDir,
            String excludeUserId) {
        PageResponse.validate(page, size);
        String needle = (search == null || search.isBlank()) ? null : search.trim().toLowerCase(Locale.ROOT);
        String roleFilter = (role == null || role.isBlank() || "ALL".equalsIgnoreCase(role))
                ? null
                : role.trim().toUpperCase(Locale.ROOT);
        String statusFilter = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null
                : status.trim().toUpperCase(Locale.ROOT);

        List<AdminUserResponse> filtered = allUsers.stream()
                .filter(u -> excludeUserId == null || !excludeUserId.equals(u.id()))
                .filter(u -> roleFilter == null || roleFilter.equalsIgnoreCase(u.role()))
                .filter(u -> statusFilter == null || statusFilter.equalsIgnoreCase(u.status()))
                .filter(u -> {
                    if (needle == null) return true;
                    String fullName = u.fullName() != null ? u.fullName().toLowerCase(Locale.ROOT) : "";
                    String email = u.email() != null ? u.email().toLowerCase(Locale.ROOT) : "";
                    return fullName.contains(needle) || email.contains(needle);
                })
                .sorted(resolveUserComparator(sortBy, sortDir))
                .toList();

        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return PageResponse.of(filtered.subList(from, to), page, size, filtered.size());
    }

    private static Comparator<AdminUserResponse> resolveUserComparator(String sortBy, String sortDir) {
        String key = (sortBy == null || sortBy.isBlank()) ? "createdAt" : sortBy.trim();
        Comparator<AdminUserResponse> cmp = switch (key) {
            case "fullName" -> Comparator.comparing(
                    u -> u.fullName() != null ? u.fullName().toLowerCase(Locale.ROOT) : "");
            case "email" -> Comparator.comparing(
                    u -> u.email() != null ? u.email().toLowerCase(Locale.ROOT) : "");
            case "role" -> Comparator.comparing(u -> u.role() != null ? u.role() : "");
            case "status" -> Comparator.comparing(u -> u.status() != null ? u.status() : "");
            default -> Comparator.comparing(
                    u -> u.createdAt() != null ? u.createdAt() : Instant.EPOCH);
        };
        return "asc".equalsIgnoreCase(sortDir) ? cmp : cmp.reversed();
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

        List<Map<String, Object>> updatedProfiles = restClient.patch()
                .uri("/rest/v1/profiles?id=eq.{id}", userId)
                .header("apikey", secretKey)
                .header("Authorization", "Bearer " + secretKey)
                .header("Prefer", "return=representation")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", status))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (updatedProfiles == null || updatedProfiles.isEmpty()) {
            throw new DomainException(RESOURCE_NOT_FOUND, "User profile row not found.");
        }

        if ("SUSPENDED".equals(status)) {
            try {
                restClient.post().uri("/auth/v1/admin/users/{id}/logout", userId)
                        .header("apikey", secretKey)
                        .header("Authorization", "Bearer " + secretKey)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception ex) {
                log.warn("[ADMIN-USERS] Best-effort session logout failed for userId={}: {}", userId, ex.getMessage());
            }
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

    private Map<String, List<String>> fetchUserRolesBatch(Collection<String> userIds) {
        if (!isConfigured() || userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<String> distinctIds = userIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        final int chunkSize = 100;
        for (int start = 0; start < distinctIds.size(); start += chunkSize) {
            String joined = String.join(",", distinctIds.subList(start, Math.min(start + chunkSize, distinctIds.size())));
            try {
                List<Map<String, Object>> rows = restClient.get()
                        .uri("/rest/v1/user_roles?user_id=in.({ids})&select=user_id,roles(code)", joined)
                        .header("apikey", secretKey)
                        .header("Authorization", "Bearer " + secretKey)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                if (rows == null || rows.isEmpty()) {
                    continue;
                }
                for (Map<String, Object> row : rows) {
                    String uid = asString(row.get("user_id"));
                    if (uid == null || !(row.get("roles") instanceof Map<?, ?> roleObj)) {
                        continue;
                    }
                    String code = asString(roleObj.get("code"));
                    if (code != null && !code.isBlank()) {
                        result.computeIfAbsent(uid, k -> new ArrayList<>()).add(code.trim());
                    }
                }
            } catch (Exception ex) {
                log.warn("[ADMIN-USERS] Failed batch role lookup from public.user_roles: {}", ex.getMessage());
            }
        }
        return result;
    }

    private String resolveHighestRole(Map<?, ?> appMeta, Map<?, ?> userMeta, List<String> dbRoles) {
        List<String> roles = new ArrayList<>();
        if (dbRoles != null) {
            roles.addAll(dbRoles);
        }
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
