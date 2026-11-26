package com.homecare.booking.controller;

import java.util.List;
import java.util.UUID;

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
import com.homecare.booking.service.QuotationService;
import com.homecare.booking.service.QuotationService.AcceptQuotationInput;
import com.homecare.booking.service.QuotationService.CreateQuotationInput;
import com.homecare.booking.service.QuotationService.QuotationResponse;
import com.homecare.booking.service.QuotationService.RejectQuotationInput;
import com.homecare.booking.service.ServiceRequestService;
import com.homecare.booking.service.ServiceRequestService.AdjustScopeInput;
import com.homecare.booking.service.ServiceRequestService.CancelServiceRequestInput;
import com.homecare.booking.service.ServiceRequestService.CreateServiceRequestInput;
import com.homecare.booking.service.ServiceRequestService.RejectServiceRequestInput;
import com.homecare.booking.service.ServiceRequestService.SelectFallbackProviderInput;
import com.homecare.booking.service.ServiceRequestService.ServiceRequestResponse;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/service-requests")
@Validated
@RequiredArgsConstructor
public class ServiceRequestController {

    private final ServiceRequestService serviceRequestService;
    private final QuotationService quotationService;
    private final ProviderGrpcClientAdapter membershipVerifier;
    private final IdempotencyService idempotencyService;
    private final ServletApiResponseFactory responseFactory;

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), membershipVerifier);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ServiceRequestResponse>> createServiceRequest(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateServiceRequestInput input,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        String fullName = jwt != null ? jwt.getClaimAsString("full_name") : null;
        ServiceRequestResponse created = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyService.execute(
                        actor.userId(), "POST:/api/v1/service-requests", idempotencyKey, input.toString(),
                        () -> serviceRequestService.createRequest(actor.userId(), fullName, input))
                : serviceRequestService.createRequest(actor.userId(), fullName, input);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ServiceRequestResponse>>> listServiceRequests(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                serviceRequestService.listRequests(resolveActor(jwt, rawOrgId), page, size),
                httpRequest));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<ServiceRequestResponse>> getServiceRequest(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID requestId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                serviceRequestService.getRequest(resolveActor(jwt, rawOrgId), requestId),
                httpRequest));
    }

    @PostMapping("/{requestId}/provider-accept")
    public ResponseEntity<ApiResponse<ServiceRequestResponse>> providerAccept(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID requestId,
            @RequestParam(defaultValue = "0") long fixedPrice,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                serviceRequestService.providerAccept(resolveActor(jwt, rawOrgId), requestId, fixedPrice),
                httpRequest));
    }

    @PostMapping("/{requestId}/provider-reject")
    public ResponseEntity<ApiResponse<ServiceRequestResponse>> providerReject(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID requestId,
            @Valid @RequestBody RejectServiceRequestInput input,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                serviceRequestService.providerReject(resolveActor(jwt, rawOrgId), requestId, input.reason()),
                httpRequest));
    }

    @PostMapping("/{requestId}/fallback-provider")
    public ResponseEntity<ApiResponse<ServiceRequestResponse>> selectFallbackProvider(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID requestId,
            @Valid @RequestBody SelectFallbackProviderInput input,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                serviceRequestService.customerSelectFallbackProvider(actor.userId(), requestId, input),
                httpRequest));
    }

    @PostMapping("/{requestId}/adjust-scope")
    public ResponseEntity<ApiResponse<ServiceRequestResponse>> adjustScope(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID requestId,
            @RequestBody(required = false) AdjustScopeInput input,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                serviceRequestService.adjustScope(actor.userId(), requestId, input),
                httpRequest));
    }

    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<ApiResponse<ServiceRequestResponse>> cancelServiceRequest(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID requestId,
            @RequestBody(required = false) CancelServiceRequestInput input,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.CANCELLED,
                serviceRequestService.cancelServiceRequest(actor.userId(), requestId, input != null ? input.reason() : null),
                httpRequest));
    }

    @PostMapping("/{requestId}/quotations")
    public ResponseEntity<ApiResponse<QuotationResponse>> issueQuotation(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID requestId,
            @Valid @RequestBody CreateQuotationInput input,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        QuotationResponse created = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyService.execute(
                        actor.userId(), "POST:/api/v1/service-requests/" + requestId + "/quotations", idempotencyKey, input.toString(),
                        () -> quotationService.issueQuotation(actor, requestId, input))
                : quotationService.issueQuotation(actor, requestId, input);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping("/{requestId}/quotations")
    public ResponseEntity<ApiResponse<List<QuotationResponse>>> listQuotations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID requestId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                quotationService.listQuotations(resolveActor(jwt, rawOrgId), requestId),
                httpRequest));
    }

    @PostMapping("/{requestId}/quotations/{quoteId}/accept")
    public ResponseEntity<ApiResponse<QuotationResponse>> acceptQuotation(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID requestId,
            @PathVariable UUID quoteId,
            @Valid @RequestBody AcceptQuotationInput input,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        QuotationResponse accepted = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyService.execute(
                        actor.userId(), "POST:/api/v1/service-requests/" + requestId + "/quotations/" + quoteId + "/accept",
                        idempotencyKey, input.toString(),
                        () -> quotationService.acceptQuotation(actor.userId(), requestId, quoteId, input))
                : quotationService.acceptQuotation(actor.userId(), requestId, quoteId, input);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                accepted,
                httpRequest));
    }

    @PostMapping("/{requestId}/quotations/{quoteId}/reject")
    public ResponseEntity<ApiResponse<QuotationResponse>> rejectQuotation(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID requestId,
            @PathVariable UUID quoteId,
            @Valid @RequestBody RejectQuotationInput input,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        QuotationResponse rejected = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyService.execute(
                        actor.userId(), "POST:/api/v1/service-requests/" + requestId + "/quotations/" + quoteId + "/reject",
                        idempotencyKey, input.toString(),
                        () -> quotationService.rejectQuotation(actor.userId(), requestId, quoteId, input))
                : quotationService.rejectQuotation(actor.userId(), requestId, quoteId, input);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                rejected,
                httpRequest));
    }
}
