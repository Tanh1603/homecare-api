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
import com.homecare.common.security.KeycloakJwtConverterSupport;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@Validated
@RequiredArgsConstructor
public class BookingController {

    private final BookingLifecycleService service;
    private final IdempotencyService idempotencyService;
    private final ServletApiResponseFactory responseFactory;

    @PostMapping("/bookings")
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
                .body(responseFactory.created("Booking created", created, httpRequest));
    }

    @GetMapping("/bookings")
    public ResponseEntity<ApiResponse<PageResponse<BookingResponse>>> listBookings(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        List<BookingResponse> items = service.listBookings(jwt.getSubject(),
                hasAnyRole(jwt, "TECHNICIAN", "ORGANIZATION", "ADMIN"));
        return ResponseEntity.ok(responseFactory.ok(
                "Bookings retrieved",
                PageResponse.of(items, page, size, items.size()),
                httpRequest));
    }

    @GetMapping("/bookings/{bookingId}")
    public ResponseEntity<ApiResponse<BookingResponse>> getBooking(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        BookingResponse booking = service.getBooking(
                jwt.getSubject(),
                hasAnyRole(jwt, "TECHNICIAN", "ORGANIZATION", "ADMIN"),
                bookingId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(booking.version()))
                .body(responseFactory.ok("Booking retrieved", booking, httpRequest));
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    public ResponseEntity<ApiResponse<BookingResponse>> cancelBooking(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) CancelBookingRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = service.cancelBooking(
                jwt.getSubject(),
                hasAnyRole(jwt, "ADMIN", "ORGANIZATION"),
                bookingId,
                ifMatch,
                request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok("Booking cancelled", updated, httpRequest));
    }

    @PostMapping("/bookings/{bookingId}/work-updates")
    public ResponseEntity<ApiResponse<BookingResponse>> addWorkUpdate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID bookingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CreateWorkUpdateRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = service.appendWorkUpdate(jwt.getSubject(), bookingId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok("Work update recorded", updated, httpRequest));
    }

    private boolean hasAnyRole(Jwt jwt, String... roles) {
        return KeycloakJwtConverterSupport.hasAnyRole(jwt, roles);
    }
}
