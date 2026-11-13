package com.homecare.notification.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.notification.dto.NotificationDtos.DeviceRegistrationResponse;
import com.homecare.notification.dto.NotificationDtos.NotificationResponse;
import com.homecare.notification.dto.NotificationDtos.RegisterDeviceRequest;
import com.homecare.notification.service.NotificationInboxService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationInboxService service;
    private final ServletApiResponseFactory responseFactory;

    @GetMapping("/notifications")
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> listNotifications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        List<NotificationResponse> items = service.listInbox(jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(
                "Notifications retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @PostMapping("/notifications/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID notificationId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                "Notification marked as read",
                service.markAsRead(jwt.getSubject(), notificationId),
                httpRequest));
    }

    @PostMapping("/notification-devices")
    public ResponseEntity<ApiResponse<DeviceRegistrationResponse>> registerDevice(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegisterDeviceRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(
                        ApiMessages.CREATED,
                        service.registerDevice(jwt.getSubject(), request),
                        httpRequest));
    }

    @DeleteMapping("/notification-devices/{deviceId}")
    public ResponseEntity<ApiResponse<Void>> deactivateDevice(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deviceId,
            HttpServletRequest httpRequest) {
        service.deactivateDevice(jwt.getSubject(), deviceId);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.DELETED, null, httpRequest));
    }
}
