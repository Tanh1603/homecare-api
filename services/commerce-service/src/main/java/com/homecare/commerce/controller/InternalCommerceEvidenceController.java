package com.homecare.commerce.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.commerce.dto.CommerceDtos.VerifyOrderLineReviewEligibilityRequest;
import com.homecare.commerce.dto.CommerceDtos.VerifyOrderLineReviewEligibilityResponse;
import com.homecare.commerce.service.CommerceDomainService;
import com.homecare.common.error.DomainException;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/internal/v1/order-lines")
@RequiredArgsConstructor
public class InternalCommerceEvidenceController {

    private final CommerceDomainService service;
    private final ServletApiResponseFactory responseFactory;

    @Value("${homecare.internal.workload-token}")
    private String expectedWorkloadToken;

    @PostMapping("/review-eligibility")
    public ResponseEntity<ApiResponse<VerifyOrderLineReviewEligibilityResponse>> checkEligibility(
            @RequestHeader(value = "X-Internal-Workload-Token", required = false) String token,
            @Valid @RequestBody VerifyOrderLineReviewEligibilityRequest request,
            HttpServletRequest httpRequest) {
        if (token == null || !expectedWorkloadToken.equals(token)) {
            throw new DomainException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED",
                    "Invalid internal workload token.");
        }
        return ResponseEntity.ok(responseFactory.ok(
                "Order line review eligibility checked",
                service.verifyOrderLineReviewEligibility(request.orderLineId(), request.requesterProfileId()),
                httpRequest));
    }
}
