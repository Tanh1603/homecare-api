package com.homecare.booking.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.booking.dto.BookingDtos.VerifyBookingReviewEligibilityRequest;
import com.homecare.booking.dto.BookingDtos.VerifyBookingReviewEligibilityResponse;
import com.homecare.booking.service.BookingLifecycleService;
import com.homecare.common.error.DomainException;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/internal/v1/bookings")
@RequiredArgsConstructor
public class InternalBookingEvidenceController {

    private final BookingLifecycleService service;
    private final ServletApiResponseFactory responseFactory;

    @Value("${homecare.internal.workload-token}")
    private String expectedWorkloadToken;

    @PostMapping("/review-eligibility")
    public ResponseEntity<ApiResponse<VerifyBookingReviewEligibilityResponse>> checkEligibility(
            @RequestHeader(value = "X-Internal-Workload-Token", required = false) String token,
            @Valid @RequestBody VerifyBookingReviewEligibilityRequest request,
            HttpServletRequest httpRequest) {
        if (token == null || !expectedWorkloadToken.equals(token)) {
            throw new DomainException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED",
                    "Invalid internal workload token.");
        }
        return ResponseEntity.ok(responseFactory.ok(
                "Eligibility checked",
                service.verifyReviewEligibility(request.bookingId(), request.requesterProfileId()),
                httpRequest));
    }
}
