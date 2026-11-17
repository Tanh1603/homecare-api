package com.homecare.provider.controller;

import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.dto.ProviderDtos.ProviderResponse;
import com.homecare.provider.dto.ProviderDtos.ScheduleWindowResponse;
import com.homecare.provider.dto.ProviderDtos.ServiceOfferingResponse;
import com.homecare.provider.dto.ProviderDtos.TechnicianResponse;
import com.homecare.provider.dto.ProviderDtos.UpdateScheduleWindowRequest;
import com.homecare.provider.security.ProviderAccessPolicy;
import com.homecare.provider.service.ProviderDomainService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class ProviderPublicAndTechController {

    private final ProviderDomainService service;
    private final ProviderAccessPolicy accessPolicy;
    private final ServletApiResponseFactory responseFactory;

    @GetMapping("/services")
    public ResponseEntity<ApiResponse<PageResponse<ServiceOfferingResponse>>> listServices(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        UUID effectiveProviderId = includeInactive
                ? resolveAuthorizedInactiveScope(jwt, rawOrgId, providerId, RbacConstants.PERM_SERVICE_MANAGE)
                : providerId;
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.offeringsPage(category, effectiveProviderId, q, includeInactive, page, size),
                httpRequest));
    }

    @GetMapping("/services/{serviceId}")
    public ResponseEntity<ApiResponse<ServiceOfferingResponse>> getService(
            @PathVariable UUID serviceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.getOffering(serviceId),
                httpRequest));
    }

    @GetMapping("/providers")
    public ResponseEntity<ApiResponse<PageResponse<ProviderResponse>>> listProviders(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.providersPage(city, q, page, size),
                httpRequest));
    }

    @GetMapping("/providers/{providerId}")
    public ResponseEntity<ApiResponse<ProviderResponse>> getProvider(
            @PathVariable UUID providerId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.getProvider(providerId),
                httpRequest));
    }

    @GetMapping("/technicians")
    public ResponseEntity<ApiResponse<PageResponse<TechnicianResponse>>> listTechnicians(
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        UUID effectiveProviderId = includeInactive
                ? resolveAuthorizedInactiveScope(jwt, rawOrgId, providerId, RbacConstants.PERM_TECHNICIAN_MANAGE)
                : providerId;
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.techniciansPage(effectiveProviderId, q, includeInactive, page, size),
                httpRequest));
    }

    private UUID resolveAuthorizedInactiveScope(Jwt jwt, String rawOrgId, UUID requestedProviderId, String orgPermission) {
        if (jwt == null) {
            throw new com.homecare.common.error.DomainException(com.homecare.common.error.CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), accessPolicy);
        if (actor.isGlobalAdmin() && actor.mode() == com.homecare.common.security.AccessMode.PERSONAL) {
            return requestedProviderId;
        }
        if (actor.mode() == com.homecare.common.security.AccessMode.ORGANIZATION
                && actor.hasOrgPermission(orgPermission)) {
            if (requestedProviderId != null && !requestedProviderId.equals(actor.organizationId())) {
                throw new com.homecare.common.error.DomainException(com.homecare.common.error.CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            return actor.organizationId();
        }
        throw new com.homecare.common.error.DomainException(com.homecare.common.error.CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    @GetMapping("/technicians/{technicianId}")
    public ResponseEntity<ApiResponse<TechnicianResponse>> getTechnician(
            @PathVariable UUID technicianId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.getTechnician(technicianId),
                httpRequest));
    }

    @GetMapping("/technicians/{technicianId}/schedules")
    public ResponseEntity<ApiResponse<PageResponse<ScheduleWindowResponse>>> listSchedules(
            @PathVariable String technicianId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        PageResponse.validate(page, size);
        ActorContext actor = jwt != null
                ? ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), accessPolicy)
                : null;
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.listScheduleWindowsPage(technicianId, actor, page, size),
                httpRequest));
    }

    @PostMapping("/technicians/{technicianId}/schedules")
    public ResponseEntity<ApiResponse<ScheduleWindowResponse>> createSchedule(
            @PathVariable String technicianId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @Valid @RequestBody CreateScheduleWindowRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), accessPolicy);
        ScheduleWindowResponse created = service.createScheduleWindow(technicianId, actor, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, EtagSupport.format(created.version()))
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @PatchMapping("/technicians/{technicianId}/schedules/{windowId}")
    public ResponseEntity<ApiResponse<ScheduleWindowResponse>> updateSchedule(
            @PathVariable String technicianId,
            @PathVariable UUID windowId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateScheduleWindowRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), accessPolicy);
        ScheduleWindowResponse updated = service.updateScheduleWindow(technicianId, actor, windowId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }
}
