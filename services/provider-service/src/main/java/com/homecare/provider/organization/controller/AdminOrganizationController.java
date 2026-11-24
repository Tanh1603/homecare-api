package com.homecare.provider.organization.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationAppealResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.RejectOrganizationRequest;
import com.homecare.provider.organization.entity.LegalEntityType;
import com.homecare.provider.organization.entity.OrganizationAppealEntity;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.service.OrganizationDomainService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/organizations")
@PreAuthorize(RbacConstants.EXPR_ORG_REVIEW)
@Validated
@RequiredArgsConstructor
public class AdminOrganizationController {

    private final OrganizationDomainService organizationService;
    private final ServletApiResponseFactory responseFactory;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<OrganizationResponse>>> listOrganizations(
            @RequestParam(required = false) OrganizationStatus status,
            @RequestParam(required = false) LegalEntityType legalEntityType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        PageResponse.validate(page, size);
        String effectiveSearch = (q != null && !q.isBlank()) ? q : search;
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                organizationService.listAllForAdminPage(
                        status, legalEntityType, effectiveSearch, sortBy, sortDir, page, size),
                httpRequest));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<OrganizationResponse>> approveOrganization(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        OrganizationResponse approved = organizationService.approveOrganization(id, jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.APPROVED, approved, httpRequest));
    }

    @PostMapping("/{id}/approve-upgrade")
    public ResponseEntity<ApiResponse<OrganizationResponse>> approveLegalEntityUpgrade(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        OrganizationResponse approved = organizationService.approveLegalEntityUpgrade(id, jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.APPROVED, approved, httpRequest));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<OrganizationResponse>> rejectOrganization(
            @PathVariable UUID id,
            @Valid @RequestBody RejectOrganizationRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        String adminId = jwt != null ? jwt.getSubject() : "ADMIN";
        OrganizationResponse rejected = organizationService.rejectOrganization(id, request.reason(), adminId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.REJECTED, rejected, httpRequest));
    }

    public record SanctionOrganizationRequest(
            @NotBlank String reason,
            String evidenceRefs,
            Boolean safetyInterventionFlag) {}

    public record ResolveAppealRequest(
            boolean approved,
            String reviewNote,
            OrganizationStatus targetStatus) {}

    @PostMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<OrganizationResponse>> suspendOrganization(
            @PathVariable UUID id,
            @Valid @RequestBody SanctionOrganizationRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        String adminId = jwt != null ? jwt.getSubject() : "ADMIN";
        OrganizationResponse suspended = organizationService.suspendOrganization(
                id, request.reason(), request.evidenceRefs(),
                Boolean.TRUE.equals(request.safetyInterventionFlag()), adminId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.SUSPENDED, suspended, httpRequest));
    }

    @PostMapping("/{id}/ban")
    public ResponseEntity<ApiResponse<OrganizationResponse>> banOrganization(
            @PathVariable UUID id,
            @Valid @RequestBody SanctionOrganizationRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        OrganizationResponse banned = organizationService.banOrganization(
                id, request.reason(), request.evidenceRefs(),
                Boolean.TRUE.equals(request.safetyInterventionFlag()), jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.SUSPENDED, banned, httpRequest));
    }

    @PostMapping("/{id}/appeals/{appealId}/resolve")
    public ResponseEntity<ApiResponse<OrganizationAppealResponse>> resolveAppeal(
            @PathVariable UUID id,
            @PathVariable UUID appealId,
            @Valid @RequestBody ResolveAppealRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        OrganizationAppealEntity resolved = organizationService.resolveAppeal(
                id, appealId, request.approved(), request.reviewNote(), request.targetStatus(), jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED, OrganizationAppealResponse.from(resolved), httpRequest));
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<ApiResponse<OrganizationResponse>> reactivateOrganization(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        OrganizationResponse reactivated = organizationService.reactivateOrganization(id);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.APPROVED, reactivated, httpRequest));
    }
}
