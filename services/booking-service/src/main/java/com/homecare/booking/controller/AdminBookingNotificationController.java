package com.homecare.booking.controller;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

import com.homecare.booking.dto.BookingExecutionDtos.AdminNotificationFailedBookingDetailResponse;
import com.homecare.booking.dto.BookingExecutionDtos.AdminNotificationFailedBookingResponse;
import com.homecare.booking.dto.BookingExecutionDtos.RetryCompletionNotificationRequest;
import com.homecare.booking.service.BookingAcceptanceService;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/bookings/notification-failed")
@Validated
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminBookingNotificationController {

    private final BookingAcceptanceService acceptanceService;
    private final ServletApiResponseFactory responseFactory;

    private ActorContext requireAdmin(Jwt jwt) {
        ActorContext admin = ActorContextResolver.resolvePersonalOnly(jwt, null);
        if (!admin.isGlobalAdmin()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Admin role required.");
        }
        return admin;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminNotificationFailedBookingResponse>>> listNotificationFailed(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        requireAdmin(jwt);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                acceptanceService.listNotificationFailedBookings(page, size),
                httpRequest));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<AdminNotificationFailedBookingDetailResponse>> getNotificationFailed(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        requireAdmin(jwt);
        AdminNotificationFailedBookingDetailResponse detail =
                acceptanceService.getNotificationFailedBooking(bookingId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(detail.version()))
                .body(responseFactory.ok(ApiMessages.RETRIEVED, detail, httpRequest));
    }

    @PostMapping("/{bookingId}/retry")
    public ResponseEntity<ApiResponse<AdminNotificationFailedBookingDetailResponse>> retryCompletionNotification(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @Valid @RequestBody(required = false) RetryCompletionNotificationRequest request,
            HttpServletRequest httpRequest) {
        ActorContext admin = requireAdmin(jwt);
        AdminNotificationFailedBookingDetailResponse updated = acceptanceService.retryCompletionNotification(
                admin, bookingId, ifMatch, request != null ? request.note() : null);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }
}
