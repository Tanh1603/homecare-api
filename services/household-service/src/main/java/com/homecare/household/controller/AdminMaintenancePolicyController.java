package com.homecare.household.controller;

import java.util.UUID;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.household.dto.HouseholdDtos.CreateMaintenancePolicyRequest;
import com.homecare.household.dto.HouseholdDtos.MaintenancePolicyResponse;
import com.homecare.household.dto.HouseholdDtos.UpdateMaintenancePolicyRequest;
import com.homecare.household.service.HouseholdDomainService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/maintenance-policies")
@Validated
@RequiredArgsConstructor
public class AdminMaintenancePolicyController {

    private final HouseholdDomainService service;
    private final ServletApiResponseFactory responseFactory;

    private ActorContext resolveAdmin(Jwt jwt) {
        return ActorContextResolver.resolvePersonalOnly(jwt, null);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<MaintenancePolicyResponse>>> listPolicies(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.listMaintenancePoliciesForAdmin(resolveAdmin(jwt), category, status, page, size),
                httpRequest));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MaintenancePolicyResponse>> createPolicy(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateMaintenancePolicyRequest request,
            HttpServletRequest httpRequest) {
        MaintenancePolicyResponse created = service.createMaintenancePolicyForAdmin(resolveAdmin(jwt), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @PatchMapping("/{policyId}")
    public ResponseEntity<ApiResponse<MaintenancePolicyResponse>> updatePolicy(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID policyId,
            @RequestBody UpdateMaintenancePolicyRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                service.updateMaintenancePolicyForAdmin(resolveAdmin(jwt), policyId, request),
                httpRequest));
    }
}
