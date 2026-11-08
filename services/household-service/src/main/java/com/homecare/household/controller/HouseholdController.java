package com.homecare.household.controller;

import java.util.List;
import java.util.UUID;

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

import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
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
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class HouseholdController {

    private final HouseholdDomainService service;
    private final ServletApiResponseFactory responseFactory;

    @PostMapping("/households")
    public ResponseEntity<ApiResponse<HouseholdResponse>> createHousehold(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateHouseholdRequest request,
            HttpServletRequest httpRequest) {
        HouseholdResponse created = service.createHousehold(jwt.getSubject(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created("Household created", created, httpRequest));
    }

    @GetMapping("/households")
    public ResponseEntity<ApiResponse<PageResponse<HouseholdResponse>>> listHouseholds(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        List<HouseholdResponse> items = service.listHouseholds(jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(
                "Households retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @GetMapping("/households/{householdId}")
    public ResponseEntity<ApiResponse<HouseholdResponse>> getHousehold(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID householdId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Household retrieved",
                service.getHousehold(jwt.getSubject(), householdId),
                httpRequest));
    }

    @PatchMapping("/households/{householdId}")
    public ResponseEntity<ApiResponse<HouseholdResponse>> updateHousehold(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID householdId,
            @RequestBody UpdateHouseholdRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Household updated",
                service.updateHousehold(jwt.getSubject(), householdId, request),
                httpRequest));
    }

    @DeleteMapping("/households/{householdId}")
    public ResponseEntity<ApiResponse<Void>> deleteHousehold(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID householdId,
            HttpServletRequest httpRequest) {
        service.deleteHousehold(jwt.getSubject(), householdId);
        return ResponseEntity.ok(responseFactory.ok("Household deleted", null, httpRequest));
    }

    @PostMapping("/devices")
    public ResponseEntity<ApiResponse<DeviceResponse>> createDevice(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateDeviceRequest request,
            HttpServletRequest httpRequest) {
        DeviceResponse created = service.createDevice(jwt.getSubject(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, EtagSupport.format(created.version()))
                .body(responseFactory.created("Device created", created, httpRequest));
    }

    @GetMapping("/devices")
    public ResponseEntity<ApiResponse<PageResponse<DeviceResponse>>> listDevices(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID householdId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        List<DeviceResponse> items = service.listDevices(jwt.getSubject(), householdId);
        return ResponseEntity.ok(responseFactory.ok(
                "Devices retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @GetMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<DeviceResponse>> getDevice(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deviceId,
            HttpServletRequest httpRequest) {
        DeviceResponse device = service.getDevice(jwt.getSubject(), deviceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(device.version()))
                .body(responseFactory.ok("Device retrieved", device, httpRequest));
    }

    @PatchMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<DeviceResponse>> updateDevice(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deviceId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateDeviceRequest request,
            HttpServletRequest httpRequest) {
        DeviceResponse updated = service.updateDevice(jwt.getSubject(), deviceId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok("Device updated", updated, httpRequest));
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<Void>> deleteDevice(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deviceId,
            HttpServletRequest httpRequest) {
        service.deleteDevice(jwt.getSubject(), deviceId);
        return ResponseEntity.ok(responseFactory.ok("Device deleted", null, httpRequest));
    }

    @PostMapping("/devices/{deviceId}/warranties")
    public ResponseEntity<ApiResponse<WarrantyResponse>> createWarranty(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateWarrantyRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        "Warranty created",
                        service.createWarranty(jwt.getSubject(), deviceId, request),
                        httpRequest));
    }

    @GetMapping("/devices/{deviceId}/warranties")
    public ResponseEntity<ApiResponse<List<WarrantyResponse>>> listWarranties(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deviceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Warranties retrieved",
                service.listWarranties(jwt.getSubject(), deviceId),
                httpRequest));
    }

    @PatchMapping("/warranties/{warrantyId}")
    public ResponseEntity<ApiResponse<WarrantyResponse>> updateWarranty(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID warrantyId,
            @RequestBody UpdateWarrantyRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Warranty updated",
                service.updateWarranty(jwt.getSubject(), warrantyId, request),
                httpRequest));
    }

    @DeleteMapping("/warranties/{warrantyId}")
    public ResponseEntity<ApiResponse<Void>> deleteWarranty(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID warrantyId,
            HttpServletRequest httpRequest) {
        service.deleteWarranty(jwt.getSubject(), warrantyId);
        return ResponseEntity.ok(responseFactory.ok("Warranty deleted", null, httpRequest));
    }

    @PostMapping("/devices/{deviceId}/maintenance-schedules")
    public ResponseEntity<ApiResponse<MaintenanceScheduleResponse>> createSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateMaintenanceScheduleRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        "Maintenance schedule created",
                        service.createSchedule(jwt.getSubject(), deviceId, request),
                        httpRequest));
    }

    @GetMapping("/devices/{deviceId}/maintenance-schedules")
    public ResponseEntity<ApiResponse<List<MaintenanceScheduleResponse>>> listSchedules(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deviceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Maintenance schedules retrieved",
                service.listSchedules(jwt.getSubject(), deviceId),
                httpRequest));
    }

    @PatchMapping("/maintenance-schedules/{scheduleId}")
    public ResponseEntity<ApiResponse<MaintenanceScheduleResponse>> updateSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID scheduleId,
            @RequestBody UpdateMaintenanceScheduleRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Maintenance schedule updated",
                service.updateSchedule(jwt.getSubject(), scheduleId, request),
                httpRequest));
    }

    @DeleteMapping("/maintenance-schedules/{scheduleId}")
    public ResponseEntity<ApiResponse<Void>> deleteSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID scheduleId,
            HttpServletRequest httpRequest) {
        service.deleteSchedule(jwt.getSubject(), scheduleId);
        return ResponseEntity.ok(responseFactory.ok("Maintenance schedule deleted", null, httpRequest));
    }

    @PostMapping("/devices/{deviceId}/care-history")
    public ResponseEntity<ApiResponse<CareRecordResponse>> createCareRecord(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateCareRecordRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        "Care record created",
                        service.createCareRecord(jwt.getSubject(), deviceId, request),
                        httpRequest));
    }

    @GetMapping("/devices/{deviceId}/care-history")
    public ResponseEntity<ApiResponse<List<CareRecordResponse>>> listCareRecords(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deviceId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Care records retrieved",
                service.listCareRecords(jwt.getSubject(), deviceId),
                httpRequest));
    }

    @PatchMapping("/care-history/{recordId}")
    public ResponseEntity<ApiResponse<CareRecordResponse>> updateCareRecord(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recordId,
            @RequestBody UpdateCareRecordRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Care record updated",
                service.updateCareRecord(jwt.getSubject(), recordId, request),
                httpRequest));
    }

    @DeleteMapping("/care-history/{recordId}")
    public ResponseEntity<ApiResponse<Void>> deleteCareRecord(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recordId,
            HttpServletRequest httpRequest) {
        service.deleteCareRecord(jwt.getSubject(), recordId);
        return ResponseEntity.ok(responseFactory.ok("Care record deleted", null, httpRequest));
    }
}
