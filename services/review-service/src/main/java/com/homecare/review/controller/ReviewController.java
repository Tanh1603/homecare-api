package com.homecare.review.controller;

import lombok.RequiredArgsConstructor;

import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.review.dto.ReviewDtos.CreateProductReviewRequest;
import com.homecare.review.dto.ReviewDtos.CreateServiceReviewRequest;
import com.homecare.review.dto.ReviewDtos.ReviewResponse;
import com.homecare.review.dto.ReviewDtos.UpdateReviewRequest;
import com.homecare.review.security.ReviewAccessPolicy;
import com.homecare.review.service.ReviewDomainService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewDomainService service;
    private final ReviewAccessPolicy accessPolicy;
    private final ServletApiResponseFactory responseFactory;

    @PatchMapping("/products/{productId}/reviews/{reviewId}")
    public ResponseEntity<?> updateProductReview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID productId,
            @PathVariable UUID reviewId,
            @Valid @RequestBody UpdateReviewRequest input,
            HttpServletRequest request) {
        ActorContext actor = resolvePersonal(jwt, rawOrgId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.UPDATED, service.updateProductReview(actor.userId(), productId, reviewId, input), request));
    }

    @DeleteMapping("/products/{productId}/reviews/{reviewId}")
    public ResponseEntity<?> deleteProductReview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID productId,
            @PathVariable UUID reviewId) {
        ActorContext actor = resolvePersonal(jwt, rawOrgId);
        service.deleteProductReview(actor.userId(), productId, reviewId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/service-reviews")
    public ResponseEntity<ApiResponse<ReviewResponse>> createServiceReview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @Valid @RequestBody CreateServiceReviewRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolvePersonal(jwt, rawOrgId);
        String authorName = SupabaseJwtConverterSupport.extractDisplayName(jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        ApiMessages.CREATED,
                        service.createServiceReview(actor.userId(), authorName, request),
                        httpRequest));
    }

    @PatchMapping("/service-reviews/{reviewId}")
    public ResponseEntity<ApiResponse<ReviewResponse>> updateServiceReview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID reviewId,
            @Valid @RequestBody UpdateReviewRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolvePersonal(jwt, rawOrgId);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                service.updateServiceReview(actor.userId(), reviewId, request),
                httpRequest));
    }

    @DeleteMapping("/service-reviews/{reviewId}")
    public ResponseEntity<Void> deleteServiceReview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID reviewId) {
        ActorContext actor = resolvePersonal(jwt, rawOrgId);
        service.deleteServiceReview(actor.userId(), reviewId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/service-reviews/by-booking/{bookingId}")
    public ResponseEntity<ApiResponse<ReviewResponse>> getServiceReviewByBooking(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolvePersonal(jwt, rawOrgId);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.getServiceReviewByBooking(actor.userId(), bookingId),
                httpRequest));
    }

    @GetMapping("/providers/{providerId}/service-reviews")
    public ResponseEntity<ApiResponse<PageResponse<ReviewResponse>>> listProviderReviews(
            @PathVariable UUID providerId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.providerReviewsPage(providerId, page, size),
                httpRequest));
    }

    @PostMapping("/products/{productId}/reviews")
    public ResponseEntity<ApiResponse<ReviewResponse>> createProductReview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID productId,
            @Valid @RequestBody CreateProductReviewRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolvePersonal(jwt, rawOrgId);
        String authorName = SupabaseJwtConverterSupport.extractDisplayName(jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        ApiMessages.CREATED,
                        service.createProductReview(actor.userId(), authorName, productId, request),
                        httpRequest));
    }

    private ActorContext resolvePersonal(Jwt jwt, String rawOrgId) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        accessPolicy.assertCanMutatePersonalReview(actor);
        return actor;
    }

    @GetMapping("/products/{productId}/reviews")
    public ResponseEntity<ApiResponse<PageResponse<ReviewResponse>>> listProductReviews(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.productReviewsPage(productId, page, size),
                httpRequest));
    }
}
