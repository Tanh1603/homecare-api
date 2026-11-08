package com.homecare.household.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.common.error.DomainException;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.household.dto.HouseholdDtos.VerifyDeviceReferenceRequest;
import com.homecare.household.dto.HouseholdDtos.VerifyDeviceReferenceResponse;
import com.homecare.household.dto.HouseholdDtos.VerifyWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.VerifyWarrantyResponse;
import com.homecare.household.service.HouseholdDomainService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class InternalDeviceReferenceController {

    private final HouseholdDomainService service;
    private final ServletApiResponseFactory responseFactory;

    @Value("${homecare.internal.workload-token}")
    private String expectedWorkloadToken;

    @PostMapping("/device-references/verify")
    public ResponseEntity<ApiResponse<VerifyDeviceReferenceResponse>> verify(
            @RequestHeader(value = "X-Internal-Workload-Token", required = false) String token,
            @Valid @RequestBody VerifyDeviceReferenceRequest request,
            HttpServletRequest httpRequest) {
        requireWorkloadToken(token);
        VerifyDeviceReferenceResponse result = service.verifyDeviceReference(
                request.deviceId(),
                request.requesterProfileId());
        return ResponseEntity.ok(responseFactory.ok("Device reference verified", result, httpRequest));
    }

    @PostMapping("/warranties/verify")
    public ResponseEntity<ApiResponse<VerifyWarrantyResponse>> verifyWarranty(
            @RequestHeader(value = "X-Internal-Workload-Token", required = false) String token,
            @Valid @RequestBody VerifyWarrantyRequest request,
            HttpServletRequest httpRequest) {
        requireWorkloadToken(token);
        VerifyWarrantyResponse result = service.verifyWarrantyEligibility(
                request.warrantyId(),
                request.requesterProfileId());
        return ResponseEntity.ok(responseFactory.ok("Warranty eligibility verified", result, httpRequest));
    }

    private void requireWorkloadToken(String token) {
        if (token == null || !expectedWorkloadToken.equals(token)) {
            throw new DomainException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED",
                    "Invalid internal workload token.");
        }
    }
}
