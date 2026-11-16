package com.homecare.provider.controller;

import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.dto.ProviderDtos.*;
import com.homecare.provider.security.ProviderAccessPolicy;
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
    private final ProviderAccessPolicy accessPolicy;
    private final ServletApiResponseFactory responses;

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), accessPolicy);
    }

    @PatchMapping("/providers/{id}")
    public ResponseEntity<?> updateProvider(@PathVariable UUID id, @Valid @RequestBody ProviderInput input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok("Updated", service.updateProvider(id, input, resolveActor(jwt, rawOrgId)), request));
    }

    @PostMapping("/services")
    public ResponseEntity<?> createOffering(@Valid @RequestBody OfferingInput input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.status(201).body(responses.created("Created", service.saveOffering(null, input, resolveActor(jwt, rawOrgId)), request));
    }

    @PatchMapping("/services/{id}")
    public ResponseEntity<?> updateOffering(@PathVariable UUID id, @Valid @RequestBody OfferingInput input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok("Updated", service.saveOffering(id, input, resolveActor(jwt, rawOrgId)), request));
    }

    @DeleteMapping("/services/{id}")
    public ResponseEntity<?> deleteOffering(@PathVariable UUID id,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteOffering(id, resolveActor(jwt, rawOrgId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/technicians/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            HttpServletRequest request) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        return ResponseEntity.ok(responses.ok("Retrieved", service.me(actor.userId()), request));
    }

    @PostMapping("/technicians")
    public ResponseEntity<?> createTechnician(@Valid @RequestBody TechnicianInput input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.status(201).body(responses.created("Created", service.saveTechnician(null, input, resolveActor(jwt, rawOrgId)), request));
    }

    @PatchMapping("/technicians/{id}")
    public ResponseEntity<?> updateTechnician(@PathVariable UUID id, @Valid @RequestBody TechnicianInput input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok("Updated", service.saveTechnician(id, input, resolveActor(jwt, rawOrgId)), request));
    }

    @DeleteMapping("/technicians/{id}")
    public ResponseEntity<?> deleteTechnician(@PathVariable UUID id,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteTechnician(id, resolveActor(jwt, rawOrgId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/technicians/{id}/schedules/{windowId}")
    public ResponseEntity<?> deleteSchedule(@PathVariable String id, @PathVariable UUID windowId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteSchedule(id, windowId, resolveActor(jwt, rawOrgId), ifMatch);
        return ResponseEntity.noContent().build();
    }
}
