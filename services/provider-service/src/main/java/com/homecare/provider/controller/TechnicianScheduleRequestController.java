package com.homecare.provider.controller;

import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.dto.RecurringScheduleDtos.CreateScheduleChangeRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.ReviewScheduleChangeRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.ScheduleChangeRequestResponse;
import com.homecare.provider.security.ProviderAccessPolicy;
import com.homecare.provider.service.TechnicianScheduleRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TechnicianScheduleRequestController {

    private final TechnicianScheduleRequestService requestService;
    private final ProviderAccessPolicy accessPolicy;
    private final ServletApiResponseFactory responseFactory;

    @PostMapping("/technicians/me/schedule-requests")
    public ResponseEntity<ApiResponse<ScheduleChangeRequestResponse>> submitMyRequest(
            @Valid @RequestBody CreateScheduleChangeRequest body,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(
                jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        ScheduleChangeRequestResponse created = requestService.submitMyRequest(actor, body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, request));
    }

    @GetMapping("/technicians/me/schedule-requests")
    public ResponseEntity<ApiResponse<List<ScheduleChangeRequestResponse>>> listMyRequests(
            @RequestParam(required = false) String status,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(
                jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        List<ScheduleChangeRequestResponse> items = requestService.listMyRequests(actor, status);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, items, request));
    }

    @DeleteMapping("/technicians/me/schedule-requests/{requestId}")
    public ResponseEntity<ApiResponse<ScheduleChangeRequestResponse>> cancelMyRequest(
            @PathVariable UUID requestId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(
                jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        ScheduleChangeRequestResponse cancelled = requestService.cancelMyRequest(actor, requestId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.UPDATED, cancelled, request));
    }

    @GetMapping("/organizations/{organizationId}/technicians/schedule-requests")
    public ResponseEntity<ApiResponse<List<ScheduleChangeRequestResponse>>> listOrganizationRequests(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) String status,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        UUID headerOrgId = ActorContextResolver.parseOrganizationId(rawOrgId);
        ActorContext actor = ActorContextResolver.resolve(jwt, headerOrgId, accessPolicy);
        List<ScheduleChangeRequestResponse> items = requestService.listOrganizationRequests(
                organizationId, status, actor);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, items, request));
    }

    @PostMapping("/organizations/{organizationId}/technicians/schedule-requests/{requestId}/review")
    public ResponseEntity<ApiResponse<ScheduleChangeRequestResponse>> reviewRequest(
            @PathVariable UUID organizationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ReviewScheduleChangeRequest body,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        UUID headerOrgId = ActorContextResolver.parseOrganizationId(rawOrgId);
        ActorContext actor = ActorContextResolver.resolve(jwt, headerOrgId, accessPolicy);
        ScheduleChangeRequestResponse reviewed = requestService.reviewRequest(
                organizationId, requestId, body, actor);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.UPDATED, reviewed, request));
    }
}
