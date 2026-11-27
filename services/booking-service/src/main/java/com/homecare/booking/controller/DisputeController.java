package com.homecare.booking.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.DisputeDtos.DisputeEvidenceResponse;
import com.homecare.booking.dto.DisputeDtos.DisputeResponse;
import com.homecare.booking.dto.DisputeDtos.OpenDisputeRequest;
import com.homecare.booking.dto.DisputeDtos.ProposeResolutionRequest;
import com.homecare.booking.dto.DisputeDtos.ProviderDisputeResponseRequest;
import com.homecare.booking.dto.DisputeDtos.RequestSlaActionRequest;
import com.homecare.booking.dto.DisputeDtos.ResolveDisputeRequest;
import com.homecare.booking.service.DisputeService;
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
public class DisputeController {

    private final DisputeService disputeService;
    private final ProviderGrpcClientAdapter providerGrpcClient;
    private final IdempotencyService idempotencyService;
    private final ServletApiResponseFactory responseFactory;

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), providerGrpcClient);
    }

    @PostMapping("/bookings/{bookingId}/disputes")
    public ResponseEntity<ApiResponse<DisputeResponse>> openDispute(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID bookingId,
            @Valid @RequestBody OpenDisputeRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        DisputeResponse created = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyService.execute(
                        actor.userId(), "POST:/api/v1/bookings/" + bookingId + "/disputes",
                        idempotencyKey, request.toString(),
                        () -> disputeService.openDispute(actor, bookingId, request))
                : disputeService.openDispute(actor, bookingId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, EtagSupport.format(created.version()))
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping("/bookings/{bookingId}/disputes")
    public ResponseEntity<ApiResponse<List<DisputeResponse>>> listBookingDisputes(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                disputeService.listBookingDisputes(resolveActor(jwt, rawOrgId), bookingId),
                httpRequest));
    }

    @GetMapping("/disputes")
    public ResponseEntity<ApiResponse<PageResponse<DisputeResponse>>> listDisputes(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                disputeService.listDisputes(resolveActor(jwt, rawOrgId), status, page, size),
                httpRequest));
    }

    @GetMapping("/disputes/{disputeId}")
    public ResponseEntity<ApiResponse<DisputeResponse>> getDispute(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID disputeId,
            HttpServletRequest httpRequest) {
        DisputeResponse dispute = disputeService.getDispute(resolveActor(jwt, rawOrgId), disputeId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(dispute.version()))
                .body(responseFactory.ok(ApiMessages.RETRIEVED, dispute, httpRequest));
    }

    @PostMapping("/disputes/{disputeId}/provider-response")
    public ResponseEntity<ApiResponse<DisputeResponse>> submitProviderResponse(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID disputeId,
            @Valid @RequestBody ProviderDisputeResponseRequest request,
            HttpServletRequest httpRequest) {
        DisputeResponse updated = disputeService.submitProviderResponse(
                resolveActor(jwt, rawOrgId), disputeId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping(value = "/disputes/{disputeId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DisputeEvidenceResponse>> uploadDisputeEvidence(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID disputeId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String note,
            HttpServletRequest httpRequest) throws Exception {
        DisputeEvidenceResponse created = disputeService.uploadDisputeEvidence(
                resolveActor(jwt, rawOrgId), disputeId, file.getOriginalFilename(),
                file.getContentType(), file.getBytes(), note);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping("/admin/disputes")
    public ResponseEntity<ApiResponse<PageResponse<DisputeResponse>>> adminListDisputes(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        ActorContext admin = ActorContextResolver.resolvePersonalOnly(jwt, null);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                disputeService.adminListDisputes(admin, status, page, size),
                httpRequest));
    }

    @PostMapping("/admin/disputes/{disputeId}/request-provider-response")
    public ResponseEntity<ApiResponse<DisputeResponse>> adminRequestProviderResponse(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID disputeId,
            @RequestBody(required = false) RequestSlaActionRequest request,
            HttpServletRequest httpRequest) {
        ActorContext admin = ActorContextResolver.resolvePersonalOnly(jwt, null);
        DisputeResponse updated = disputeService.adminRequestProviderResponse(admin, disputeId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/admin/disputes/{disputeId}/request-customer-evidence")
    public ResponseEntity<ApiResponse<DisputeResponse>> adminRequestCustomerEvidence(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID disputeId,
            @RequestBody(required = false) RequestSlaActionRequest request,
            HttpServletRequest httpRequest) {
        ActorContext admin = ActorContextResolver.resolvePersonalOnly(jwt, null);
        DisputeResponse updated = disputeService.adminRequestCustomerEvidence(admin, disputeId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/admin/disputes/{disputeId}/propose-resolution")
    public ResponseEntity<ApiResponse<DisputeResponse>> adminProposeResolution(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID disputeId,
            @Valid @RequestBody ProposeResolutionRequest request,
            HttpServletRequest httpRequest) {
        ActorContext admin = ActorContextResolver.resolvePersonalOnly(jwt, null);
        DisputeResponse updated = disputeService.adminProposeResolution(admin, disputeId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/admin/disputes/{disputeId}/resolve")
    public ResponseEntity<ApiResponse<DisputeResponse>> adminResolveDispute(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID disputeId,
            @Valid @RequestBody ResolveDisputeRequest request,
            HttpServletRequest httpRequest) {
        ActorContext admin = ActorContextResolver.resolvePersonalOnly(jwt, null);
        DisputeResponse updated = disputeService.adminResolveDispute(admin, disputeId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }
}
