package com.homecare.provider.organization.controller;

import static com.homecare.common.error.CommonErrorCode.INTERNAL_ERROR;
import static com.homecare.common.error.CommonErrorCode.OPERATION_FORBIDDEN;

import com.homecare.common.error.DomainException;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.organization.dto.OrganizationDtos.AdminUserResponse;
import com.homecare.provider.organization.service.SupabaseRoleClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import com.homecare.provider.config.ProviderAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize(RbacConstants.EXPR_ORG_REVIEW)
@Validated
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);
    private static final String BLACKLIST_USER_KEY_PREFIX = "auth:blacklist:user:";

    private final SupabaseRoleClient supabaseRoleClient;
    private final ServletApiResponseFactory responseFactory;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ProviderAuthProperties authProperties;

    @Autowired
    public AdminUserController(
            SupabaseRoleClient supabaseRoleClient,
            ServletApiResponseFactory responseFactory,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ProviderAuthProperties authProperties) {
        this.supabaseRoleClient = supabaseRoleClient;
        this.responseFactory = responseFactory;
        this.redisTemplateProvider = redisTemplateProvider;
        this.authProperties = authProperties;
    }

    public AdminUserController(
            SupabaseRoleClient supabaseRoleClient,
            ServletApiResponseFactory responseFactory,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(supabaseRoleClient, responseFactory, redisTemplateProvider, new ProviderAuthProperties());
    }

    public record StatusRequest(@NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status) {}

    @PatchMapping("/{userId}/status")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('admin:all')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateStatus(@PathVariable UUID userId,
            @Valid @RequestBody StatusRequest input,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        if (userId.toString().equals(jwt.getSubject()) && "SUSPENDED".equals(input.status())) {
            throw new DomainException(OPERATION_FORBIDDEN);
        }
        supabaseRoleClient.updateUserStatus(userId, input.status());
        syncUserBlacklist(userId, input.status());
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.UPDATED, Map.of("id", userId, "status", input.status()), request));
    }

    private void syncUserBlacklist(UUID userId, String status) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        String key = BLACKLIST_USER_KEY_PREFIX + userId;
        try {
            if ("SUSPENDED".equals(status)) {
                redis.opsForValue().set(key, "SUSPENDED", authProperties.getUserBlacklistTtl());
            } else {
                redis.delete(key);
            }
        } catch (Exception ex) {
            log.warn("Failed to sync Redis user blacklist for userId={}: {}", userId, ex.getMessage());
            if ("SUSPENDED".equals(status)) {
                throw new DomainException(INTERNAL_ERROR, "Failed to revoke active user session.");
            }
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminUserResponse>>> listUsers(
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        PageResponse.validate(page, size);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                supabaseRoleClient.listUsers(page, size),
                httpRequest));
    }
}
