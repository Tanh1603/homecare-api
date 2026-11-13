package com.homecare.booking.controller;

import java.util.List;
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

import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.CancelBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateWorkUpdateRequest;
import com.homecare.booking.service.BookingLifecycleService;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/bookings")
@Validated
@RequiredArgsConstructor
public class BookingController {

    private final BookingLifecycleService service;
    private final IdempotencyService idempotencyService;
    private final ServletApiResponseFactory responseFactory;

    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request,
            HttpServletRequest httpRequest) {
        String sub = jwt.getSubject();
        BookingResponse created;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String payloadSummary = request.serviceId() + "|" + request.deviceId() + "|"
                    + request.preferredStart() + "|" + request.address();
            created = idempotencyService.execute(
                    sub,
                    "POST:/api/v1/bookings",
                    idempotencyKey,
                    payloadSummary,
                    () -> service.createBooking(sub, request));
        } else {
            created = service.createBooking(sub, request);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, EtagSupport.format(created.version()))
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<BookingResponse>>> listBookings(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) UUID orgIdHeader,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        List<BookingResponse> items = service.listBookings(
                jwt.getSubject(),
                isPrivilegedBookingReader(jwt),
                isGlobalAdmin(jwt) ? null : orgIdHeader);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<BookingResponse>> getBooking(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) UUID orgIdHeader,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        BookingResponse booking = service.getBooking(
                jwt.getSubject(),
                isPrivilegedBookingReader(jwt),
                bookingId,
                isGlobalAdmin(jwt) ? null : orgIdHeader);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(booking.version()))
                .body(responseFactory.ok(ApiMessages.RETRIEVED, booking, httpRequest));
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<ApiResponse<BookingResponse>> cancelBooking(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) UUID orgIdHeader,
            @PathVariable UUID bookingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) CancelBookingRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = service.cancelBooking(
                jwt.getSubject(),
                SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                        jwt,
                        new String[]{RbacConstants.ROLE_ADMIN, RbacConstants.ROLE_ORGANIZATION},
                        RbacConstants.PERM_BOOKING_DISPATCH, RbacConstants.PERM_ADMIN_ALL),
                bookingId,
                ifMatch,
                request,
                isGlobalAdmin(jwt) ? null : orgIdHeader);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.CANCELLED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/work-updates")
    public ResponseEntity<ApiResponse<BookingResponse>> addWorkUpdate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CreateWorkUpdateRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = service.appendWorkUpdate(jwt.getSubject(), bookingId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    private boolean isGlobalAdmin(Jwt jwt) {
        return SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                jwt, new String[]{RbacConstants.ROLE_ADMIN}, RbacConstants.PERM_ADMIN_ALL);
    }

    private boolean isPrivilegedBookingReader(Jwt jwt) {
        return SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                jwt,
                new String[]{RbacConstants.ROLE_TECHNICIAN, RbacConstants.ROLE_ORGANIZATION, RbacConstants.ROLE_ADMIN},
                RbacConstants.PERM_BOOKING_DISPATCH, RbacConstants.PERM_BOOKING_UPDATE_STATUS, RbacConstants.PERM_ADMIN_ALL);
    }
}
