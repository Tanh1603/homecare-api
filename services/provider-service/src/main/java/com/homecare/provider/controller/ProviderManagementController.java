package com.homecare.provider.controller;

import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.dto.ProviderDtos.*;
import com.homecare.provider.service.ProviderDomainService;
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
public class ProviderManagementController {
    private final ProviderDomainService service;
    private final ServletApiResponseFactory responses;

    private boolean admin(Jwt jwt) {
        return SupabaseJwtConverterSupport.hasAnyRoleOrPermission(jwt, new String[]{RbacConstants.ROLE_ADMIN}, RbacConstants.PERM_ADMIN_ALL);
    }

    @PatchMapping("/providers/{id}")
    public ResponseEntity<?> updateProvider(@PathVariable UUID id, @Valid @RequestBody ProviderInput input,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID orgId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok("Updated", service.updateProvider(id, input, orgId, jwt.getSubject(), admin(jwt)), request));
    }

    @PostMapping("/services")
    public ResponseEntity<?> createOffering(@Valid @RequestBody OfferingInput input,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID orgId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.status(201).body(responses.created("Created", service.saveOffering(null, input, orgId, jwt.getSubject(), admin(jwt)), request));
    }

    @PatchMapping("/services/{id}")
    public ResponseEntity<?> updateOffering(@PathVariable UUID id, @Valid @RequestBody OfferingInput input,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID orgId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok("Updated", service.saveOffering(id, input, orgId, jwt.getSubject(), admin(jwt)), request));
    }

    @DeleteMapping("/services/{id}")
    public ResponseEntity<?> deleteOffering(@PathVariable UUID id,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID orgId,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteOffering(id, orgId, jwt.getSubject(), admin(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/technicians/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok("Retrieved", service.me(jwt.getSubject()), request));
    }

    @PostMapping("/technicians")
    public ResponseEntity<?> createTechnician(@Valid @RequestBody TechnicianInput input,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID orgId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.status(201).body(responses.created("Created", service.saveTechnician(null, input, orgId, jwt.getSubject(), admin(jwt)), request));
    }

    @PatchMapping("/technicians/{id}")
    public ResponseEntity<?> updateTechnician(@PathVariable UUID id, @Valid @RequestBody TechnicianInput input,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID orgId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok("Updated", service.saveTechnician(id, input, orgId, jwt.getSubject(), admin(jwt)), request));
    }

    @DeleteMapping("/technicians/{id}")
    public ResponseEntity<?> deleteTechnician(@PathVariable UUID id,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID orgId,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteTechnician(id, orgId, jwt.getSubject(), admin(jwt));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/technicians/{id}/schedules/{windowId}")
    public ResponseEntity<?> deleteSchedule(@PathVariable String id, @PathVariable UUID windowId,
            @RequestHeader(value = "X-Organization-Id", required = false) UUID orgId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteSchedule(id, windowId, jwt.getSubject(), orgId, admin(jwt), ifMatch);
        return ResponseEntity.noContent().build();
    }
}
