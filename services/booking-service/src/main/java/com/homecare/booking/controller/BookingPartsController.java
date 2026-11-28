package com.homecare.booking.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.PartsDtos.CancelPartsRequest;
import com.homecare.booking.dto.PartsDtos.CreatePartsRequest;
import com.homecare.booking.dto.PartsDtos.PartsRequestResponse;
import com.homecare.booking.dto.PartsDtos.PartsUsageResponse;
import com.homecare.booking.dto.PartsDtos.RecordPartsUsageRequest;
import com.homecare.booking.dto.PartsDtos.RefuseCustomerPartRequest;
import com.homecare.booking.dto.PartsDtos.ReviewPartsRequest;
import com.homecare.booking.service.BookingPartsService;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.servlet.ServletApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/bookings/{bookingId}")
@Validated
@RequiredArgsConstructor
public class BookingPartsController {

    private final BookingPartsService partsService;
    private final ProviderGrpcClientAdapter providerGrpcClient;
    private final IdempotencyService idempotencyService;
    private final ServletApiResponseFactory responseFactory;

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), providerGrpcClient);
    }

    @PostMapping("/parts-requests")
    public ResponseEntity<ApiResponse<PartsRequestResponse>> createPartsRequest(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CreatePartsRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Idempotency-Key header is required.");
        }
        PartsRequestResponse created = idempotencyService.execute(
                actor.userId(), "POST:/api/v1/bookings/" + bookingId + "/parts-requests",
                idempotencyKey, request.toString(),
                () -> partsService.createPartsRequest(actor, bookingId, request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, EtagSupport.format(created.version()))
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping("/parts-requests")
    public ResponseEntity<ApiResponse<List<PartsRequestResponse>>> listPartsRequests(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                partsService.listPartsRequests(resolveActor(jwt, rawOrgId), bookingId),
                httpRequest));
    }

    @PostMapping("/parts-requests/{requestId}/review")
    public ResponseEntity<ApiResponse<PartsRequestResponse>> reviewPartsRequest(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ReviewPartsRequest request,
            HttpServletRequest httpRequest) {
        PartsRequestResponse updated = partsService.reviewPartsRequest(
                resolveActor(jwt, rawOrgId), bookingId, requestId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/parts-requests/{requestId}/refuse-customer-part")
    public ResponseEntity<ApiResponse<PartsRequestResponse>> refuseCustomerPart(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @PathVariable UUID requestId,
            @Valid @RequestBody RefuseCustomerPartRequest request,
            HttpServletRequest httpRequest) {
        PartsRequestResponse updated = partsService.refuseCustomerPart(
                resolveActor(jwt, rawOrgId), bookingId, requestId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/parts-requests/{requestId}/cancel")
    public ResponseEntity<ApiResponse<PartsRequestResponse>> cancelPartsRequest(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @PathVariable UUID requestId,
            @Valid @RequestBody(required = false) CancelPartsRequest request,
            HttpServletRequest httpRequest) {
        PartsRequestResponse updated = partsService.cancelPartsRequest(
                resolveActor(jwt, rawOrgId), bookingId, requestId, ifMatch,
                request != null ? request.reason() : null);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/parts-usage")
    public ResponseEntity<ApiResponse<PartsUsageResponse>> recordPartsUsage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID bookingId,
            @Valid @RequestBody RecordPartsUsageRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Idempotency-Key header is required.");
        }
        PartsUsageResponse created = idempotencyService.execute(
                actor.userId(), "POST:/api/v1/bookings/" + bookingId + "/parts-usage",
                idempotencyKey, request.toString(),
                () -> partsService.recordPartsUsage(actor, bookingId, request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping("/parts-usage")
    public ResponseEntity<ApiResponse<List<PartsUsageResponse>>> listPartsUsage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                partsService.listPartsUsage(resolveActor(jwt, rawOrgId), bookingId),
                httpRequest));
    }
}
