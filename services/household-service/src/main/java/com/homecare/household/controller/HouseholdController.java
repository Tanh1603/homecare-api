package com.homecare.household.controller;

import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.household.client.ProviderOrgClient;
import com.homecare.household.dto.HouseholdDtos.CareRecordResponse;
import com.homecare.household.dto.HouseholdDtos.CreateCareRecordRequest;
import com.homecare.household.dto.HouseholdDtos.CreateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.CreateMaintenanceScheduleRequest;
import com.homecare.household.dto.HouseholdDtos.CreateWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.DeviceResponse;
import com.homecare.household.dto.HouseholdDtos.HouseholdResponse;
import com.homecare.household.dto.HouseholdDtos.MaintenanceScheduleResponse;
import com.homecare.household.dto.HouseholdDtos.UpdateCareRecordRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateMaintenanceScheduleRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.WarrantyResponse;
import com.homecare.household.service.HouseholdDomainService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class HouseholdController {

    private final HouseholdDomainService service;
    private final ProviderOrgClient providerOrgClient;
    private final ServletApiResponseFactory responseFactory;

    private ActorContext resolvePersonal(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
    }

    @PostMapping("/households")
    public ResponseEntity<ApiResponse<HouseholdResponse>> createHousehold(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @Valid @RequestBody CreateHouseholdRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolvePersonal(jwt, rawOrgId);
        HouseholdResponse created = service.createHousehold(actor.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping("/households")
    public ResponseEntity<ApiResponse<PageResponse<HouseholdResponse>>> listHouseholds(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolvePersonal(jwt, rawOrgId);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.householdsPage(actor.userId(), page, size),
                httpRequest));
    }

    @GetMapping("/households/{householdId}")
    public ResponseEntity<ApiResponse<HouseholdResponse>> getHousehold(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID householdId,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolvePersonal(jwt, rawOrgId);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.getHousehold(actor.userId(), householdId),
                httpRequest));
    }

    @PatchMapping("/households/{householdId}")
    public ResponseEntity<ApiResponse<HouseholdResponse>> updateHousehold(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID householdId,
            @RequestBody UpdateHouseholdRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                service.updateHousehold(resolvePersonal(jwt, rawOrgId).userId(), householdId, request),
                httpRequest));
    }

    @DeleteMapping("/households/{householdId}")
    public ResponseEntity<ApiResponse<Void>> deleteHousehold(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID householdId,
            HttpServletRequest httpRequest) {
        service.deleteHousehold(resolvePersonal(jwt, rawOrgId).userId(), householdId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.DELETED, null, httpRequest));
    }

    @PostMapping("/devices")
    public ResponseEntity<ApiResponse<DeviceResponse>> createDevice(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @Valid @RequestBody CreateDeviceRequest request,
            HttpServletRequest httpRequest) {
        DeviceResponse created = service.createDevice(resolvePersonal(jwt, rawOrgId).userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, EtagSupport.format(created.version()))
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping("/devices")
    public ResponseEntity<ApiResponse<PageResponse<DeviceResponse>>> listDevices(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(required = false) UUID householdId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.devicesPage(resolvePersonal(jwt, rawOrgId).userId(), householdId, page, size),
                httpRequest));
    }

    @GetMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<DeviceResponse>> getDevice(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID deviceId,
            HttpServletRequest httpRequest) {
        DeviceResponse device = service.getDevice(resolvePersonal(jwt, rawOrgId).userId(), deviceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(device.version()))
                .body(responseFactory.ok(ApiMessages.RETRIEVED, device, httpRequest));
    }

    @PatchMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<DeviceResponse>> updateDevice(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID deviceId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateDeviceRequest request,
            HttpServletRequest httpRequest) {
        DeviceResponse updated = service.updateDevice(resolvePersonal(jwt, rawOrgId).userId(), deviceId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<Void>> deleteDevice(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID deviceId,
            HttpServletRequest httpRequest) {
        service.deleteDevice(resolvePersonal(jwt, rawOrgId).userId(), deviceId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.DELETED, null, httpRequest));
    }

    @PostMapping("/devices/{deviceId}/warranties")
    public ResponseEntity<ApiResponse<WarrantyResponse>> createWarranty(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateWarrantyRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        ApiMessages.CREATED,
                        service.createWarranty(resolvePersonal(jwt, rawOrgId).userId(),
                                SupabaseJwtConverterSupport.extractDisplayName(jwt), deviceId, request),
                        httpRequest));
    }

    @GetMapping("/devices/{deviceId}/warranties")
    public ResponseEntity<ApiResponse<PageResponse<WarrantyResponse>>> listWarranties(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID deviceId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        PageResponse.validate(page, size);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.listWarrantiesPage(resolvePersonal(jwt, rawOrgId).userId(), deviceId, page, size),
                httpRequest));
    }

    @PatchMapping("/warranties/{warrantyId}")
    public ResponseEntity<ApiResponse<WarrantyResponse>> updateWarranty(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID warrantyId,
            @RequestBody UpdateWarrantyRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                service.updateWarranty(resolvePersonal(jwt, rawOrgId).userId(), warrantyId, request),
                httpRequest));
    }

    @GetMapping("/warranties")
    public ResponseEntity<ApiResponse<PageResponse<WarrantyResponse>>> listWarrantiesForOrganization(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        UUID orgIdHeader = ActorContextResolver.parseOrganizationId(rawOrgId);
        ActorContext actor = ActorContextResolver.resolve(jwt, orgIdHeader, providerOrgClient);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED,
                service.warrantiesPage(actor, organizationId, page, size), httpRequest));
    }

    @GetMapping("/maintenance-schedules")
    public ResponseEntity<?> allSchedules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.schedulesPage(resolvePersonal(jwt, rawOrgId).userId(), page, size),
                request));
    }
    @DeleteMapping("/warranties/{warrantyId}")
    public ResponseEntity<ApiResponse<Void>> deleteWarranty(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID warrantyId,
            HttpServletRequest httpRequest) {
        service.deleteWarranty(resolvePersonal(jwt, rawOrgId).userId(), warrantyId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.DELETED, null, httpRequest));
    }

    @PostMapping("/devices/{deviceId}/maintenance-schedules")
    public ResponseEntity<ApiResponse<MaintenanceScheduleResponse>> createSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateMaintenanceScheduleRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        ApiMessages.CREATED,
                        service.createSchedule(resolvePersonal(jwt, rawOrgId).userId(), deviceId, request),
                        httpRequest));
    }

    @GetMapping("/devices/{deviceId}/maintenance-schedules")
    public ResponseEntity<ApiResponse<PageResponse<MaintenanceScheduleResponse>>> listSchedules(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID deviceId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        PageResponse.validate(page, size);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.listSchedulesPage(resolvePersonal(jwt, rawOrgId).userId(), deviceId, page, size),
                httpRequest));
    }

    @PatchMapping("/maintenance-schedules/{scheduleId}")
    public ResponseEntity<ApiResponse<MaintenanceScheduleResponse>> updateSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID scheduleId,
            @RequestBody UpdateMaintenanceScheduleRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                service.updateSchedule(resolvePersonal(jwt, rawOrgId).userId(), scheduleId, request),
                httpRequest));
    }

    @DeleteMapping("/maintenance-schedules/{scheduleId}")
    public ResponseEntity<ApiResponse<Void>> deleteSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID scheduleId,
            HttpServletRequest httpRequest) {
        service.deleteSchedule(resolvePersonal(jwt, rawOrgId).userId(), scheduleId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.DELETED, null, httpRequest));
    }

    @PostMapping("/devices/{deviceId}/care-history")
    public ResponseEntity<ApiResponse<CareRecordResponse>> createCareRecord(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateCareRecordRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        ApiMessages.CREATED,
                        service.createCareRecord(resolvePersonal(jwt, rawOrgId).userId(), deviceId, request),
                        httpRequest));
    }

    @GetMapping("/devices/{deviceId}/care-history")
    public ResponseEntity<ApiResponse<PageResponse<CareRecordResponse>>> listCareRecords(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID deviceId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        PageResponse.validate(page, size);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                service.listCareRecordsPage(resolvePersonal(jwt, rawOrgId).userId(), deviceId, page, size),
                httpRequest));
    }

    @PatchMapping("/care-history/{recordId}")
    public ResponseEntity<ApiResponse<CareRecordResponse>> updateCareRecord(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID recordId,
            @RequestBody UpdateCareRecordRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                service.updateCareRecord(resolvePersonal(jwt, rawOrgId).userId(), recordId, request),
                httpRequest));
    }

    @DeleteMapping("/care-history/{recordId}")
    public ResponseEntity<ApiResponse<Void>> deleteCareRecord(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID recordId,
            HttpServletRequest httpRequest) {
        service.deleteCareRecord(resolvePersonal(jwt, rawOrgId).userId(), recordId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.DELETED, null, httpRequest));
    }
}
