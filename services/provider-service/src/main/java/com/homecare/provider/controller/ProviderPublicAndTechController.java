package com.homecare.provider.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.common.error.DomainException;
import com.homecare.common.security.KeycloakJwtConverterSupport;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.dto.ProviderDtos.ProviderResponse;
import com.homecare.provider.dto.ProviderDtos.ScheduleWindowResponse;
import com.homecare.provider.dto.ProviderDtos.ServiceOfferingResponse;
import com.homecare.provider.dto.ProviderDtos.TechnicianResponse;
import com.homecare.provider.dto.ProviderDtos.UpdateScheduleWindowRequest;
import com.homecare.provider.service.ProviderDomainService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class ProviderPublicAndTechController {

    private final ProviderDomainService service;
    private final ServletApiResponseFactory responseFactory;

    @GetMapping("/services")
    public ResponseEntity<ApiResponse<PageResponse<ServiceOfferingResponse>>> listServices(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        List<ServiceOfferingResponse> items = service.listOfferings(category);
        return ResponseEntity.ok(responseFactory.ok(
                "Services retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @GetMapping("/services/{serviceId}")
    public ResponseEntity<ApiResponse<ServiceOfferingResponse>> getService(
            @PathVariable UUID serviceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Service retrieved",
                service.getOffering(serviceId),
                httpRequest));
    }

    @GetMapping("/providers")
    public ResponseEntity<ApiResponse<PageResponse<ProviderResponse>>> listProviders(
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        List<ProviderResponse> items = service.listProviders();
        return ResponseEntity.ok(responseFactory.ok(
                "Providers retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @GetMapping("/providers/{providerId}")
    public ResponseEntity<ApiResponse<ProviderResponse>> getProvider(
            @PathVariable UUID providerId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Provider retrieved",
                service.getProvider(providerId),
                httpRequest));
    }

    @GetMapping("/technicians")
    public ResponseEntity<ApiResponse<PageResponse<TechnicianResponse>>> listTechnicians(
            @RequestParam(required = false) UUID providerId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        List<TechnicianResponse> items = service.listTechnicians(providerId);
        return ResponseEntity.ok(responseFactory.ok(
                "Technicians retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @GetMapping("/technicians/{technicianId}")
    public ResponseEntity<ApiResponse<TechnicianResponse>> getTechnician(
            @PathVariable UUID technicianId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Technician retrieved",
                service.getTechnician(technicianId),
                httpRequest));
    }

    @GetMapping("/technicians/{technicianId}/schedules")
    public ResponseEntity<ApiResponse<List<ScheduleWindowResponse>>> listSchedules(
            @PathVariable String technicianId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        String sub = jwt != null ? jwt.getSubject() : null;
        return ResponseEntity.ok(responseFactory.ok(
                "Schedules retrieved",
                service.listScheduleWindows(technicianId, sub),
                httpRequest));
    }

    @PostMapping("/technicians/{technicianId}/schedules")
    public ResponseEntity<ApiResponse<ScheduleWindowResponse>> createSchedule(
            @PathVariable String technicianId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateScheduleWindowRequest request,
            HttpServletRequest httpRequest) {
        verifyScheduleManager(jwt);
        String sub = jwt != null ? jwt.getSubject() : null;
        ScheduleWindowResponse created = service.createScheduleWindow(technicianId, sub, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, EtagSupport.format(created.version()))
                .body(responseFactory.created("Schedule window created", created, httpRequest));
    }

    @PatchMapping("/technicians/{technicianId}/schedules/{windowId}")
    public ResponseEntity<ApiResponse<ScheduleWindowResponse>> updateSchedule(
            @PathVariable String technicianId,
            @PathVariable UUID windowId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateScheduleWindowRequest request,
            HttpServletRequest httpRequest) {
        verifyScheduleManager(jwt);
        String sub = jwt != null ? jwt.getSubject() : null;
        ScheduleWindowResponse updated = service.updateScheduleWindow(technicianId, sub, windowId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok("Schedule window updated", updated, httpRequest));
    }

    private void verifyScheduleManager(Jwt jwt) {
        if (!KeycloakJwtConverterSupport.hasAnyRole(jwt, "TECHNICIAN", "ORGANIZATION", "ADMIN")) {
            throw new DomainException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN",
                    "Only technician, organization, or admin can manage schedules.");
        }
    }
}
