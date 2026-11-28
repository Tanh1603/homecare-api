package com.homecare.household.controller;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.household.client.ProviderOrgClient;
import com.homecare.household.dto.HouseholdDtos.AdminVerifyWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.WarrantyResponse;
import com.homecare.household.service.HouseholdDomainService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/warranties")
@Validated
@RequiredArgsConstructor
public class AdminWarrantyController {

    private final HouseholdDomainService service;
    private final ProviderOrgClient providerOrgClient;
    private final ServletApiResponseFactory responseFactory;

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), providerOrgClient);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<WarrantyResponse>>> listWarranties(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(required = false) String verificationStatus,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.adminListWarranties(resolveActor(jwt, rawOrgId), verificationStatus, page, size),
                httpRequest));
    }

    @PostMapping("/{warrantyId}/verify")
    public ResponseEntity<ApiResponse<WarrantyResponse>> verifyWarranty(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID warrantyId,
            @Valid @RequestBody AdminVerifyWarrantyRequest request,
            HttpServletRequest httpRequest) {
        WarrantyResponse updated = service.adminVerifyWarranty(
                resolveActor(jwt, rawOrgId), warrantyId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }
}
