package com.homecare.commerce.controller;

import com.homecare.commerce.dto.CommerceDtos.*;
import com.homecare.commerce.service.CommerceDomainService;
import com.homecare.common.error.*;
import com.homecare.common.security.*;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommerceManagementController {
    private final CommerceDomainService service;
    private final ServletApiResponseFactory responses;
    private boolean admin(Jwt jwt) { return SupabaseJwtConverterSupport.hasAnyRoleOrPermission(jwt, new String[]{"ADMIN"}, RbacConstants.PERM_ADMIN_ALL); }
    private void requireOperator(Jwt jwt) {
        if (!SupabaseJwtConverterSupport.hasAnyRoleOrPermission(jwt, new String[]{"ADMIN", "ORGANIZATION"}, RbacConstants.PERM_ADMIN_ALL, RbacConstants.PERM_ORDER_FULFILL)) throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }
    @PostMapping("/products")
    public ResponseEntity<?> create(@Valid @RequestBody ProductInput input, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID org, HttpServletRequest request) {
        requireOperator(jwt);
        return ResponseEntity.status(201).body(responses.created("Created", service.saveProduct(null, input, org, jwt.getSubject(), admin(jwt)), request));
    }
    @PatchMapping("/products/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @Valid @RequestBody ProductInput input, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID org, HttpServletRequest request) {
        requireOperator(jwt);
        return ResponseEntity.ok(responses.ok("Updated", service.saveProduct(id, input, org, jwt.getSubject(), admin(jwt)), request));
    }
    @DeleteMapping("/products/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID org) {
        requireOperator(jwt); service.deleteProduct(id, org, jwt.getSubject(), admin(jwt));
        return ResponseEntity.noContent().build();
    }
    @PatchMapping("/products/{id}/inventory")
    public ResponseEntity<?> inventory(@PathVariable UUID id, @Valid @RequestBody InventoryInput input, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID org, HttpServletRequest request) {
        requireOperator(jwt);
        return ResponseEntity.ok(responses.ok("Updated", service.updateInventory(id, input.availableQty(), org, jwt.getSubject(), admin(jwt)), request));
    }
    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID org, HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok("Cancelled", service.cancelOrder(id, jwt.getSubject(), org, admin(jwt)), request));
    }
}
