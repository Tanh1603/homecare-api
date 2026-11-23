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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.organization.dto.OrganizationDtos.AddMemberRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.MyOrganizationMembershipResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationAppealResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationMemberResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.OrganizationResponse;
import com.homecare.provider.organization.dto.OrganizationDtos.RegisterOrganizationRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.ResubmitOrganizationRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.UpdateMemberRoleRequest;
import com.homecare.provider.organization.dto.OrganizationDtos.UpdateOrganizationRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestHeader;
import com.homecare.provider.organization.entity.LegalEntityType;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.OrganizationAppealEntity;
import com.homecare.provider.organization.security.OrgContext;
import com.homecare.provider.organization.security.RequireOrgRole;
import com.homecare.provider.organization.service.OrganizationDomainService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    public record UpgradeLegalEntityRequest(
            @NotNull LegalEntityType targetLegalEntityType,
            String taxCode,
            String identityEvidenceRefs) {}

    @PostMapping("/{id}/upgrade-legal-entity")
    public ResponseEntity<ApiResponse<OrganizationResponse>> upgradeLegalEntity(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpgradeLegalEntityRequest request,
            HttpServletRequest httpRequest) {
        OrganizationResponse updated = organizationService.requestLegalEntityUpgrade(
                id, jwt.getSubject(), request.targetLegalEntityType(),
                request.taxCode(), request.identityEvidenceRefs(), ifMatch);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    public record SubmitAppealRequest(
            @NotNull UUID enforcementId,
            @NotBlank String appealReason,
            String evidenceRefs) {}

    @PostMapping("/{id}/appeals")
    public ResponseEntity<ApiResponse<OrganizationAppealResponse>> submitAppeal(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SubmitAppealRequest request,
            HttpServletRequest httpRequest) {
        OrganizationAppealEntity created = organizationService.submitAppeal(
                id, jwt.getSubject(), request.enforcementId(), request.appealReason(), request.evidenceRefs());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, OrganizationAppealResponse.from(created), httpRequest));
    }

    @PutMapping("/{id}/resubmit")
    public ResponseEntity<ApiResponse<OrganizationResponse>> resubmitOrganization(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ResubmitOrganizationRequest request,
            HttpServletRequest httpRequest) {
        OrganizationResponse updated = organizationService.resubmitOrganization(
                id, jwt.getSubject(), ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<PageResponse<MyOrganizationMembershipResponse>>> listMyOrganizations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        PageResponse.validate(page, size);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                organizationService.listMyOrganizationsPage(jwt.getSubject(), page, size),
                httpRequest));
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

    @PatchMapping("/current")
    @RequireOrgRole({ OrgRole.ORG_OWNER, OrgRole.ORG_ADMIN })
    public ResponseEntity<ApiResponse<OrganizationResponse>> updateCurrentOrganization(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateOrganizationRequest request,
            HttpServletRequest httpRequest) {
        UUID orgId = OrgContext.get().organizationId();
        OrganizationResponse updated = organizationService.updateCurrentOrganization(orgId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @GetMapping("/current/members")
    @RequireOrgRole({ OrgRole.ORG_OWNER, OrgRole.ORG_ADMIN, OrgRole.ORG_STAFF })
    public ResponseEntity<ApiResponse<PageResponse<OrganizationMemberResponse>>> listCurrentOrgMembers(
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        PageResponse.validate(page, size);
        UUID orgId = OrgContext.get().organizationId();
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                organizationService.listMembersPage(orgId, page, size),
                httpRequest));
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
