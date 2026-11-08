package com.homecare.review.controller;

import java.util.List;
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

import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.review.dto.ReviewDtos.CreateProductReviewRequest;
import com.homecare.review.dto.ReviewDtos.CreateServiceReviewRequest;
import com.homecare.review.dto.ReviewDtos.ReviewResponse;
import com.homecare.review.dto.ReviewDtos.UpdateReviewRequest;
import com.homecare.review.service.ReviewDomainService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewDomainService service;
    private final ServletApiResponseFactory responseFactory;

    @PostMapping("/service-reviews")
    public ResponseEntity<ApiResponse<ReviewResponse>> createServiceReview(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateServiceReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        "Service review created",
                        service.createServiceReview(jwt.getSubject(), request),
                        httpRequest));
    }

    @PatchMapping("/service-reviews/{reviewId}")
    public ResponseEntity<ApiResponse<ReviewResponse>> updateServiceReview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reviewId,
            @Valid @RequestBody UpdateReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Service review updated",
                service.updateServiceReview(jwt.getSubject(), reviewId, request),
                httpRequest));
    }

    @GetMapping("/providers/{providerId}/service-reviews")
    public ResponseEntity<ApiResponse<PageResponse<ReviewResponse>>> listProviderReviews(
            @PathVariable UUID providerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        List<ReviewResponse> items = service.listProviderReviews(providerId);
        return ResponseEntity.ok(responseFactory.ok(
                "Provider reviews retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @PostMapping("/products/{productId}/reviews")
    public ResponseEntity<ApiResponse<ReviewResponse>> createProductReview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID productId,
            @Valid @RequestBody CreateProductReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        "Product review created",
                        service.createProductReview(jwt.getSubject(), productId, request),
                        httpRequest));
    }

    @GetMapping("/products/{productId}/reviews")
    public ResponseEntity<ApiResponse<PageResponse<ReviewResponse>>> listProductReviews(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        List<ReviewResponse> items = service.listProductReviews(productId);
        return ResponseEntity.ok(responseFactory.ok(
                "Product reviews retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }
}
