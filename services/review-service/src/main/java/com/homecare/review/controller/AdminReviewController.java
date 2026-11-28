package com.homecare.review.controller;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.review.dto.ReviewDtos.AdminHideReviewRequest;
import com.homecare.review.dto.ReviewDtos.AdminRestoreReviewRequest;
import com.homecare.review.dto.ReviewDtos.AdminResyncRatingRequest;
import com.homecare.review.dto.ReviewDtos.AdminResyncRatingResponse;
import com.homecare.review.dto.ReviewDtos.AdminReviewResponse;
import com.homecare.review.service.ReviewDomainService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/service-reviews")
@Validated
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminReviewController {

    private final ReviewDomainService service;
    private final ServletApiResponseFactory responseFactory;

    private ActorContext requireAdmin(Jwt jwt) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, null);
        if (!actor.isGlobalAdmin()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Admin role required.");
        }
        return actor;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminReviewResponse>>> listServiceReviews(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID providerId,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        requireAdmin(jwt);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.adminListServiceReviews(status, providerId, rating, q, page, size),
                httpRequest));
    }

    @PostMapping("/{reviewId}/hide")
    public ResponseEntity<ApiResponse<AdminReviewResponse>> hideServiceReview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID reviewId,
            @Valid @RequestBody AdminHideReviewRequest request,
            HttpServletRequest httpRequest) {
        ActorContext admin = requireAdmin(jwt);
        AdminReviewResponse hidden = service.adminHideServiceReview(
                admin.userId(), reviewId, ifMatch, request.reason());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(hidden.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, hidden, httpRequest));
    }

    @PostMapping("/{reviewId}/restore")
    public ResponseEntity<ApiResponse<AdminReviewResponse>> restoreServiceReview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID reviewId,
            @Valid @RequestBody(required = false) AdminRestoreReviewRequest request,
            HttpServletRequest httpRequest) {
        ActorContext admin = requireAdmin(jwt);
        AdminReviewResponse restored = service.adminRestoreServiceReview(
                admin.userId(), reviewId, ifMatch, request != null ? request.note() : null);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(restored.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, restored, httpRequest));
    }

    @PostMapping("/resync-rating")
    public ResponseEntity<ApiResponse<AdminResyncRatingResponse>> resyncProviderRatings(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) AdminResyncRatingRequest request,
            HttpServletRequest httpRequest) {
        requireAdmin(jwt);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                service.adminResyncRatings(request != null ? request.providerId() : null),
                httpRequest));
    }
}
