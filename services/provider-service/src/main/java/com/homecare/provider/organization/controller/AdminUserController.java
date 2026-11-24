package com.homecare.provider.organization.controller;

import static com.homecare.common.error.CommonErrorCode.INTERNAL_ERROR;
import static com.homecare.common.error.CommonErrorCode.OPERATION_FORBIDDEN;
import static com.homecare.common.error.CommonErrorCode.VALIDATION_FAILED;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.organization.dto.OrganizationDtos.AdminUserResponse;
import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;
import com.homecare.provider.organization.entity.UserEnforcementAuditEntity;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;
import com.homecare.provider.organization.repository.UserEnforcementAuditRepository;
import com.homecare.provider.organization.service.OrganizationDomainService;
import com.homecare.provider.organization.service.SupabaseRoleClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.homecare.provider.config.ProviderAuthProperties;
import tools.jackson.databind.ObjectMapper;
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
    private final UserEnforcementAuditRepository auditRepository;
    private final OrganizationDomainService organizationService;
    private final ProviderOutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public AdminUserController(
            SupabaseRoleClient supabaseRoleClient,
            ServletApiResponseFactory responseFactory,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ProviderAuthProperties authProperties,
            ObjectProvider<UserEnforcementAuditRepository> auditRepositoryProvider,
            ObjectProvider<OrganizationDomainService> organizationServiceProvider,
            ObjectProvider<ProviderOutboxEventRepository> outboxRepositoryProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.supabaseRoleClient = supabaseRoleClient;
        this.responseFactory = responseFactory;
        this.redisTemplateProvider = redisTemplateProvider;
        this.authProperties = authProperties;
        this.auditRepository = auditRepositoryProvider != null ? auditRepositoryProvider.getIfAvailable() : null;
        this.organizationService = organizationServiceProvider != null ? organizationServiceProvider.getIfAvailable() : null;
        this.outboxRepository = outboxRepositoryProvider != null ? outboxRepositoryProvider.getIfAvailable() : null;
        this.objectMapper = objectMapperProvider != null ? objectMapperProvider.getIfAvailable() : new ObjectMapper();
    }

    public AdminUserController(
            SupabaseRoleClient supabaseRoleClient,
            ServletApiResponseFactory responseFactory,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ProviderAuthProperties authProperties) {
        this(supabaseRoleClient, responseFactory, redisTemplateProvider, authProperties, null, null, null, null);
    }

    public AdminUserController(
            SupabaseRoleClient supabaseRoleClient,
            ServletApiResponseFactory responseFactory,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(supabaseRoleClient, responseFactory, redisTemplateProvider, new ProviderAuthProperties(), null, null, null, null);
    }

    public record StatusRequest(
            @NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status,
            String reason) {
        public StatusRequest(String status) {
            this(status, null);
        }
    }

    public record UserStatusResponse(UUID id, String status) {}

    @PatchMapping("/{userId}/status")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('admin:all')")
    public ResponseEntity<ApiResponse<UserStatusResponse>> updateStatus(@PathVariable UUID userId,
            @Valid @RequestBody StatusRequest input,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        if (userId.toString().equals(jwt.getSubject()) && "SUSPENDED".equals(input.status())) {
            throw new DomainException(OPERATION_FORBIDDEN);
        }
        if ("SUSPENDED".equals(input.status()) && (input.reason() == null || input.reason().isBlank())) {
            throw new DomainException(VALIDATION_FAILED, "Suspension reason is required.");
        }
        supabaseRoleClient.updateUserStatus(userId, input.status());
        syncUserBlacklist(userId, input.status());
        if ("SUSPENDED".equals(input.status())) {
            Instant now = Instant.now();
            String reason = input.reason().trim();
            if (auditRepository != null) {
                auditRepository.save(UserEnforcementAuditEntity.builder()
                        .id(UUID.randomUUID())
                        .targetUserId(userId)
                        .newStatus(input.status())
                        .reason(reason)
                        .issuedByAdmin(jwt.getSubject())
                        .issuedAt(now)
                        .build());
            }
            if (organizationService != null) {
                organizationService.handleOwnerUserSuspended(userId.toString(), reason, jwt.getSubject());
            }
            if (outboxRepository != null) {
                publishUserSuspendedEvent(userId, reason, now);
            }
        }
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.UPDATED, new UserStatusResponse(userId, input.status()), request));
    }

    private void publishUserSuspendedEvent(UUID userId, String reason, Instant now) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString());
            envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.USER_SUSPENDED);
            envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
            envelope.put(DomainEventContracts.FIELD_PRODUCER, "provider-service");
            envelope.put(DomainEventContracts.FIELD_DATA, Map.of(
                    "userId", userId.toString(),
                    "reason", reason,
                    "suspendedAt", now.toString()));
            outboxRepository.save(ProviderOutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("User")
                    .aggregateId(userId)
                    .eventType(DomainEventContracts.USER_SUSPENDED)
                    .routingKey(DomainEventContracts.USER_SUSPENDED)
                    .payloadJson(objectMapper.writeValueAsString(envelope))
                    .status(EntityStatus.PENDING.name())
                    .retryCount(0)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .build());
        } catch (Exception ex) {
            log.error("Failed to enqueue user.suspended.v1 for userId={}: {}", userId, ex.getMessage());
            throw new DomainException(INTERNAL_ERROR, "Failed to persist user suspension governance event.");
        }
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
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(defaultValue = "false") boolean excludeSelf,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        PageResponse.validate(page, size);
        String effectiveSearch = (q != null && !q.isBlank()) ? q : search;
        String excludeUserId = (excludeSelf && jwt != null) ? jwt.getSubject() : null;
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                supabaseRoleClient.listUsers(page, size, effectiveSearch, role, status, sortBy, sortDir, excludeUserId),
                httpRequest));
    }
}
