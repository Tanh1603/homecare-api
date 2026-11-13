package com.homecare.provider.organization.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.organization.dto.OrganizationDtos.AddMemberRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.MyOrganizationMembershipResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationMemberResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.RegisterOrganizationRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.UpdateMemberRoleRequest;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.security.OrgContext;
import com.homecare.provider.organization.security.RequireOrgRole;
import com.homecare.provider.organization.service.OrganizationDomainService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/organizations")
@Validated
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationDomainService organizationService;
    private final ServletApiResponseFactory responseFactory;

    @PostMapping
    public ResponseEntity<ApiResponse<OrganizationResponse>> registerOrganization(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegisterOrganizationRequest request,
            HttpServletRequest httpRequest) {
        OrganizationResponse created = organizationService.registerOrganization(jwt.getSubject(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<MyOrganizationMembershipResponse>>> listMyOrganizations(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        List<MyOrganizationMembershipResponse> list = organizationService.listMyOrganizations(jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, list, httpRequest));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrganizationResponse>> getOrganization(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        boolean isAdmin = SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                jwt, new String[]{RbacConstants.ROLE_ADMIN}, RbacConstants.PERM_ORGANIZATION_REVIEW, RbacConstants.PERM_ADMIN_ALL);
        OrganizationResponse detail = organizationService.getOrganizationDetail(id, jwt.getSubject(), isAdmin);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, detail, httpRequest));
    }

    @GetMapping("/current/members")
    @RequireOrgRole({ OrgRole.ORG_OWNER, OrgRole.ORG_ADMIN, OrgRole.ORG_STAFF })
    public ResponseEntity<ApiResponse<List<OrganizationMemberResponse>>> listCurrentOrgMembers(
            HttpServletRequest httpRequest) {
        UUID orgId = OrgContext.get().organizationId();
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, organizationService.listMembers(orgId), httpRequest));
    }

    @PostMapping("/current/members")
    @RequireOrgRole({ OrgRole.ORG_OWNER, OrgRole.ORG_ADMIN })
    public ResponseEntity<ApiResponse<OrganizationMemberResponse>> addCurrentOrgMember(
            @Valid @RequestBody AddMemberRequest request,
            HttpServletRequest httpRequest) {
        UUID orgId = OrgContext.get().organizationId();
        OrganizationMemberResponse member = organizationService.addMember(orgId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, member, httpRequest));
    }

    @PatchMapping("/current/members/{userId}")
    @RequireOrgRole({ OrgRole.ORG_OWNER, OrgRole.ORG_ADMIN })
    public ResponseEntity<ApiResponse<OrganizationMemberResponse>> updateCurrentOrgMemberRole(
            @PathVariable String userId,
            @Valid @RequestBody UpdateMemberRoleRequest request,
            HttpServletRequest httpRequest) {
        UUID orgId = OrgContext.get().organizationId();
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                organizationService.updateMemberRole(orgId, userId, request.role()),
                httpRequest));
    }

    @DeleteMapping("/current/members/{userId}")
    @RequireOrgRole({ OrgRole.ORG_OWNER, OrgRole.ORG_ADMIN })
    public ResponseEntity<ApiResponse<Void>> removeCurrentOrgMember(
            @PathVariable String userId,
            HttpServletRequest httpRequest) {
        UUID orgId = OrgContext.get().organizationId();
        organizationService.removeMember(orgId, userId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.DELETED, null, httpRequest));
    }
}
