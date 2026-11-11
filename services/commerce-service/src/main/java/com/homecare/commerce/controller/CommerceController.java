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

import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderReturnRequest;
import com.homecare.commerce.dto.CommerceDtos.CreatePaymentAttemptRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderResponse;
import com.homecare.commerce.dto.CommerceDtos.OrderReturnResponse;
import com.homecare.commerce.dto.CommerceDtos.PaymentAttemptResponse;
import com.homecare.commerce.dto.CommerceDtos.ProductResponse;
import com.homecare.commerce.dto.CommerceDtos.ResolveOrderReturnRequest;
import com.homecare.commerce.service.CommerceDomainService;
import com.homecare.common.error.DomainException;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.SupabaseJwtConverterSupport;
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
    private final IdempotencyService idempotencyService;
    private final ServletApiResponseFactory responseFactory;

    @GetMapping("/products")
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        List<ProductResponse> items = service.listProducts(category);
        return ResponseEntity.ok(responseFactory.ok(
                "Products retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProduct(
            @PathVariable UUID productId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Product retrieved",
                service.getProduct(productId),
                httpRequest));
    }

    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            HttpServletRequest httpRequest) {
        String sub = jwt.getSubject();
        OrderResponse created;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            created = idempotencyService.execute(
                    sub,
                    "POST:/api/v1/orders",
                    idempotencyKey,
                    request.toString(),
                    () -> service.createOrder(sub, request));
        } else {
            created = service.createOrder(sub, request);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, EtagSupport.format(created.version()))
                .body(responseFactory.created("Order created", created, httpRequest));
    }

    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> listOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        List<OrderResponse> items = service.listOrders(jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(
                "Orders retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            HttpServletRequest httpRequest) {
        OrderResponse order = service.getOrder(jwt.getSubject(), orderId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(order.version()))
                .body(responseFactory.ok("Order retrieved", order, httpRequest));
    }

    @PostMapping("/orders/{orderId}/payment-attempts")
    public ResponseEntity<ApiResponse<PaymentAttemptResponse>> createPaymentAttempt(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @RequestBody(required = false) CreatePaymentAttemptRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        String sub = jwt.getSubject();
        String returnUrl = body != null ? body.returnUrl() : null;
        PaymentAttemptResponse attempt;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            attempt = idempotencyService.execute(
                    sub,
                    "POST:/api/v1/orders/" + orderId + "/payment-attempts",
                    idempotencyKey,
                    orderId.toString(),
                    () -> service.createPaymentAttempt(sub, orderId, returnUrl));
        } else {
            attempt = service.createPaymentAttempt(sub, orderId, returnUrl);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created("Payment attempt created", attempt, httpRequest));
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
            HttpServletRequest httpRequest) {
        verifyOrganizationOrAdmin(jwt, "Only organization or admin can fulfill order lines.");
        return ResponseEntity.ok(responseFactory.ok(
                "Order line fulfilled",
                service.fulfillOrderLine(jwt.getSubject(), orderId, lineId),
                httpRequest));
    }

    @PostMapping("/orders/{orderId}/lines/{lineId}/returns")
    public ResponseEntity<ApiResponse<OrderReturnResponse>> createOrderReturn(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @Valid @RequestBody CreateOrderReturnRequest request,
            HttpServletRequest httpRequest) {
        OrderReturnResponse created = service.createOrderReturnRequest(
                jwt.getSubject(), orderId, lineId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created("Order return request created", created, httpRequest));
    }

    @PostMapping("/orders/{orderId}/returns/{returnId}/resolve")
    public ResponseEntity<ApiResponse<OrderReturnResponse>> resolveOrderReturn(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            @PathVariable UUID returnId,
            @Valid @RequestBody ResolveOrderReturnRequest request,
            HttpServletRequest httpRequest) {
        verifyOrganizationOrAdmin(jwt, "Only organization or admin can resolve order returns.");
        OrderReturnResponse resolved = service.resolveOrderReturnRequest(
                jwt.getSubject(), orderId, returnId, request);
        return ResponseEntity.ok(responseFactory.ok("Order return request resolved", resolved, httpRequest));
    }

    private void verifyOrganizationOrAdmin(Jwt jwt, String message) {
        if (!SupabaseJwtConverterSupport.hasAnyRole(jwt, "ORGANIZATION", "ADMIN")) {
            throw new DomainException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", message);
        }
    }

    @GetMapping("/orders/{orderId}/returns")
    public ResponseEntity<ApiResponse<List<OrderReturnResponse>>> listOrderReturns(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orderId,
            HttpServletRequest httpRequest) {
        boolean isPrivileged = SupabaseJwtConverterSupport.hasAnyRole(jwt, "ORGANIZATION", "ADMIN");
        return ResponseEntity.ok(responseFactory.ok(
                "Order return requests retrieved",
                service.listOrderReturns(jwt.getSubject(), isPrivileged, orderId),
                httpRequest));
    }
}
