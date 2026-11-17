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
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.RejectOrganizationRequest;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.service.OrganizationDomainService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
    public ResponseEntity<ApiResponse<List<OrganizationResponse>>> listOrganizations(
            @RequestParam(required = false) OrganizationStatus status,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, organizationService.listAllForAdmin(status), httpRequest));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<OrganizationResponse>> approveOrganization(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        OrganizationResponse approved = organizationService.approveOrganization(id, jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.APPROVED, approved, httpRequest));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<OrganizationResponse>> rejectOrganization(
            @PathVariable UUID id,
            @Valid @RequestBody RejectOrganizationRequest request,
            HttpServletRequest httpRequest) {
        OrganizationResponse rejected = organizationService.rejectOrganization(id, request.reason());
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.REJECTED, rejected, httpRequest));
    }

    @PostMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<OrganizationResponse>> suspendOrganization(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        OrganizationResponse suspended = organizationService.suspendOrganization(id);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.SUSPENDED, suspended, httpRequest));
    }
}
