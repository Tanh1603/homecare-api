package com.homecare.commerce.controller;

import com.homecare.commerce.client.ProviderOrgClient;
import com.homecare.commerce.dto.CommerceDtos.InventoryInput;
import com.homecare.commerce.dto.CommerceDtos.OrderResponse;
import com.homecare.commerce.dto.CommerceDtos.ProductInput;
import com.homecare.commerce.dto.CommerceDtos.ProductResponse;
import com.homecare.commerce.service.CommerceDomainService;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommerceManagementController {
    private final CommerceDomainService service;
    private final ProviderOrgClient providerOrgClient;
    private final ServletApiResponseFactory responses;

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), providerOrgClient);
    }

    @PostMapping("/products")
    public ResponseEntity<ApiResponse<ProductResponse>> create(
            @Valid @RequestBody ProductInput input,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.created(
                ApiMessages.CREATED,
                service.saveProduct(null, input, resolveActor(jwt, rawOrgId)),
                request));
    }

    @PatchMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody ProductInput input,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok(
                ApiMessages.UPDATED,
                service.saveProduct(id, input, resolveActor(jwt, rawOrgId)),
                request));
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId) {
        service.deleteProduct(id, resolveActor(jwt, rawOrgId));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/products/{id}/inventory")
    public ResponseEntity<ApiResponse<ProductResponse>> inventory(
            @PathVariable UUID id,
            @Valid @RequestBody InventoryInput input,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok(
                ApiMessages.UPDATED,
                service.updateInventory(id, input.availableQty(), resolveActor(jwt, rawOrgId)),
                request));
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancel(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok(
                ApiMessages.CANCELLED,
                service.cancelOrder(id, resolveActor(jwt, rawOrgId)),
                request));
    }
}
