package com.homecare.commerce.controller;

import java.util.List;
import java.util.Map;
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

import com.homecare.commerce.client.ProviderOrgClient;
import com.homecare.commerce.dto.CommerceDtos.ConfirmManualRefundRequest;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderReturnRequest;
import com.homecare.commerce.dto.CommerceDtos.CreatePaymentAttemptRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderResponse;
import com.homecare.commerce.dto.CommerceDtos.OrderReturnResponse;
import com.homecare.commerce.dto.CommerceDtos.PaymentAttemptResponse;
import com.homecare.commerce.dto.CommerceDtos.ProductResponse;
import com.homecare.commerce.dto.CommerceDtos.ResolveOrderReturnRequest;
import com.homecare.commerce.service.CommerceDomainService;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.SupabaseJwtConverterSupport;
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
public class CommerceController {

    private final CommerceDomainService service;
    private final ProviderOrgClient providerOrgClient;
    private final IdempotencyService idempotencyService;
    private final ServletApiResponseFactory responseFactory;

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        UUID effectiveMerchantId = includeInactive
                ? resolveAuthorizedInactiveMerchant(jwt, rawOrgId, merchantId)
                : merchantId;
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.productsPage(category, q, effectiveMerchantId, minPrice, maxPrice, inStock, sort, includeInactive, page, size),
                httpRequest));
    }

    private UUID resolveAuthorizedInactiveMerchant(Jwt jwt, String rawOrgId, UUID requestedMerchantId) {
        if (jwt == null) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), providerOrgClient);
        if (actor.isGlobalAdmin() && actor.mode() == com.homecare.common.security.AccessMode.PERSONAL) {
            return requestedMerchantId;
        }
        if (actor.mode() == com.homecare.common.security.AccessMode.ORGANIZATION
                && actor.hasOrgPermission(RbacConstants.PERM_PRODUCT_MANAGE)) {
            if (requestedMerchantId != null && !requestedMerchantId.equals(actor.organizationId())) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            return actor.organizationId();
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProduct(
            @PathVariable UUID productId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.getProduct(productId),
                httpRequest));
    }

    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            HttpServletRequest httpRequest) {
        String sub = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId))
                .userId();
        String buyerName = SupabaseJwtConverterSupport.extractDisplayName(jwt);
        OrderResponse created;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            created = idempotencyService.execute(
                    sub,
                    "POST:/api/v1/orders",
                    idempotencyKey,
                    request.toString(),
                    () -> service.createOrder(sub, buyerName, request));
        } else {
            created = service.createOrder(sub, buyerName, request);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, EtagSupport.format(created.version()))
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> listOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        UUID orgIdHeader = ActorContextResolver.parseOrganizationId(rawOrgId);
        if (orgIdHeader != null && organizationId != null && !orgIdHeader.equals(organizationId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        UUID effectiveOrgId = orgIdHeader != null ? orgIdHeader : organizationId;
        ActorContext actor = ActorContextResolver.resolve(jwt, effectiveOrgId, providerOrgClient);
        var result = service.ordersPage(actor, status, q, sort, page, size);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                result,
                httpRequest));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID orderId,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId),
                providerOrgClient);
        OrderResponse order = service.getOrderForPortal(actor, orderId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(order.version()))
                .body(responseFactory.ok(ApiMessages.RETRIEVED, order, httpRequest));
    }

    @PostMapping("/orders/{orderId}/payment-attempts")
    public ResponseEntity<ApiResponse<PaymentAttemptResponse>> createPaymentAttempt(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID orderId,
            @RequestBody(required = false) CreatePaymentAttemptRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        String sub = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId))
                .userId();
        String returnUrl = body != null ? body.returnUrl() : null;
        PaymentAttemptResponse attempt;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            attempt = idempotencyService.execute(
                    sub,
                    "POST:/api/v1/orders/" + orderId + "/payment-attempts",
                    idempotencyKey,
                    orderId + "|" + (returnUrl == null ? "" : returnUrl),
                    () -> service.createPaymentAttempt(sub, orderId, returnUrl));
        } else {
            attempt = service.createPaymentAttempt(sub, orderId, returnUrl);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, attempt, httpRequest));
    }

    @GetMapping("/payments/vnpay/ipn")
    public ResponseEntity<Map<String, String>> vnpayIpnGet(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(service.handleVnpayIpn(params));
    }

    @PostMapping("/payments/vnpay/ipn")
    public ResponseEntity<Map<String, String>> vnpayIpnPost(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(service.handleVnpayIpn(params));
    }

    @PostMapping("/orders/{orderId}/lines/{lineId}/fulfillment")
    public ResponseEntity<ApiResponse<OrderResponse>> fulfillLine(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId),
                providerOrgClient);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.PROCESSED,
                service.fulfillOrderLine(actor, orderId, lineId),
                httpRequest));
    }

    @PostMapping("/orders/{orderId}/lines/{lineId}/returns")
    public ResponseEntity<ApiResponse<OrderReturnResponse>> createOrderReturn(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @Valid @RequestBody CreateOrderReturnRequest request,
            HttpServletRequest httpRequest) {
        String sub = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId))
                .userId();
        String buyerName = SupabaseJwtConverterSupport.extractDisplayName(jwt);
        OrderReturnResponse created = service.createOrderReturnRequest(
                sub, buyerName, orderId, lineId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @PostMapping("/orders/{orderId}/returns/{returnId}/resolve")
    public ResponseEntity<ApiResponse<OrderReturnResponse>> resolveOrderReturn(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @PathVariable UUID returnId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ResolveOrderReturnRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId),
                providerOrgClient);
        OrderReturnResponse resolved = service.resolveOrderReturnRequest(actor, orderId, returnId, request, ifMatch);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(resolved.version()))
                .body(responseFactory.ok(ApiMessages.PROCESSED, resolved, httpRequest));
    }

    @PostMapping("/orders/{orderId}/returns/{returnId}/confirm-manual-refund")
    public ResponseEntity<ApiResponse<OrderReturnResponse>> confirmManualRefund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @PathVariable UUID returnId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ConfirmManualRefundRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId),
                providerOrgClient);
        OrderReturnResponse updated = service.confirmManualRefund(actor, orderId, returnId, request, ifMatch);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.PROCESSED, updated, httpRequest));
    }

    @PostMapping("/orders/{orderId}/returns/{returnId}/retry-refund")
    public ResponseEntity<ApiResponse<OrderReturnResponse>> retryRefund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @PathVariable UUID returnId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId),
                providerOrgClient);
        OrderReturnResponse updated = service.retryRefund(actor, orderId, returnId, ifMatch);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.PROCESSED, updated, httpRequest));
    }

    @GetMapping("/merchant/returns")
    public ResponseEntity<ApiResponse<PageResponse<OrderReturnResponse>>> listMerchantReturns(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId),
                providerOrgClient);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.merchantReturnsPage(actor, status, page, size),
                httpRequest));
    }

    @GetMapping("/orders/{orderId}/returns")
    public ResponseEntity<ApiResponse<PageResponse<OrderReturnResponse>>> listOrderReturns(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID orderId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        PageResponse.validate(page, size);
        ActorContext actor = ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId),
                providerOrgClient);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.listOrderReturnsPage(actor, orderId, page, size),
                httpRequest));
    }
}
