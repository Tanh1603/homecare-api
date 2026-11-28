package com.homecare.booking.controller;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.WarrantyClaimDtos.AdminVerifyWarrantyClaimRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.AppendClaimEvidenceRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.BookingWarrantySnapshotsResponse;
import com.homecare.booking.dto.WarrantyClaimDtos.RespondWarrantyClaimRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.SubmitWarrantyClaimRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.WarrantyClaimResponse;
import com.homecare.booking.service.WarrantyClaimService;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class WarrantyClaimController {

    private final WarrantyClaimService warrantyClaimService;
    private final ProviderGrpcClientAdapter providerGrpcClient;
    private final IdempotencyService idempotencyService;
    private final ServletApiResponseFactory responseFactory;

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), providerGrpcClient);
    }

    @GetMapping("/bookings/{bookingId}/warranty-snapshots")
    public ResponseEntity<ApiResponse<BookingWarrantySnapshotsResponse>> getBookingWarrantySnapshots(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                warrantyClaimService.getBookingWarrantySnapshots(resolveActor(jwt, rawOrgId), bookingId),
                httpRequest));
    }

    @PostMapping("/warranty-claims")
    public ResponseEntity<ApiResponse<WarrantyClaimResponse>> submitClaim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubmitWarrantyClaimRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Idempotency-Key header is required.");
        }
        WarrantyClaimResponse created = idempotencyService.execute(
                actor.userId(), "POST:/api/v1/warranty-claims",
                idempotencyKey, request.toString(),
                () -> warrantyClaimService.submitClaim(actor, request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, EtagSupport.format(created.version()))
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping("/warranty-claims")
    public ResponseEntity<ApiResponse<PageResponse<WarrantyClaimResponse>>> listClaims(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(required = false) String claimStatus,
            @RequestParam(required = false) String warrantyCategory,
            @RequestParam(required = false) Boolean providerNoResponse,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                warrantyClaimService.listClaims(
                        resolveActor(jwt, rawOrgId), claimStatus, warrantyCategory, providerNoResponse, page, size),
                httpRequest));
    }

    @GetMapping("/warranty-claims/{claimId}")
    public ResponseEntity<ApiResponse<WarrantyClaimResponse>> getClaim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID claimId,
            HttpServletRequest httpRequest) {
        WarrantyClaimResponse claim = warrantyClaimService.getClaim(resolveActor(jwt, rawOrgId), claimId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(claim.version()))
                .body(responseFactory.ok(ApiMessages.RETRIEVED, claim, httpRequest));
    }

    @PostMapping("/warranty-claims/{claimId}/evidence")
    public ResponseEntity<ApiResponse<WarrantyClaimResponse>> appendEvidence(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID claimId,
            @Valid @RequestBody AppendClaimEvidenceRequest request,
            HttpServletRequest httpRequest) {
        WarrantyClaimResponse updated = warrantyClaimService.appendEvidence(
                resolveActor(jwt, rawOrgId), claimId, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/warranty-claims/{claimId}/respond")
    public ResponseEntity<ApiResponse<WarrantyClaimResponse>> respondToClaim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID claimId,
            @Valid @RequestBody RespondWarrantyClaimRequest request,
            HttpServletRequest httpRequest) {
        WarrantyClaimResponse updated = warrantyClaimService.respondToClaim(
                resolveActor(jwt, rawOrgId), claimId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/warranty-claims/{claimId}/close")
    public ResponseEntity<ApiResponse<WarrantyClaimResponse>> closeClaim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID claimId,
            HttpServletRequest httpRequest) {
        WarrantyClaimResponse updated = warrantyClaimService.closeClaim(
                resolveActor(jwt, rawOrgId), claimId, ifMatch);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @GetMapping("/admin/warranty-claims")
    public ResponseEntity<ApiResponse<PageResponse<WarrantyClaimResponse>>> adminListClaims(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(required = false) String claimStatus,
            @RequestParam(required = false) String warrantyCategory,
            @RequestParam(required = false) Boolean providerNoResponse,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                warrantyClaimService.adminListClaims(
                        resolveActor(jwt, rawOrgId), claimStatus, warrantyCategory, providerNoResponse, page, size),
                httpRequest));
    }

    @PostMapping("/admin/warranty-claims/{claimId}/verify")
    public ResponseEntity<ApiResponse<WarrantyClaimResponse>> adminVerifyClaim(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID claimId,
            @Valid @RequestBody AdminVerifyWarrantyClaimRequest request,
            HttpServletRequest httpRequest) {
        WarrantyClaimResponse updated = warrantyClaimService.adminVerifyClaim(
                resolveActor(jwt, rawOrgId), claimId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }
}
