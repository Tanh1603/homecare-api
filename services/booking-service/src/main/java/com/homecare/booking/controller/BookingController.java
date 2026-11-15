package com.homecare.booking.controller;

import lombok.RequiredArgsConstructor;

import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.CancelBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateWorkUpdateRequest;
import com.homecare.booking.service.BookingLifecycleService;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
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
            @RequestParam(required = false) String status,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) UUID orgIdHeader,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        boolean globalAdmin = isGlobalAdmin(jwt);
        boolean orgOperator = isOrgOperator(jwt);
        boolean technician = isTechnician(jwt);
        if (!globalAdmin && orgOperator && orgIdHeader == null) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        PageResponse<BookingResponse> result = service.listBookingsPage(
                jwt.getSubject(),
                globalAdmin,
                orgOperator,
                technician,
                globalAdmin ? orgIdHeader : orgIdHeader,
                page,
                size, status);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                result,
                httpRequest));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<BookingResponse>> getBooking(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) UUID orgIdHeader,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        boolean globalAdmin = isGlobalAdmin(jwt);
        boolean orgOperator = isOrgOperator(jwt);
        if (!globalAdmin && orgOperator && orgIdHeader == null) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        BookingResponse booking = service.getBooking(
                jwt.getSubject(),
                globalAdmin,
                orgOperator,
                isTechnician(jwt),
                bookingId,
                globalAdmin ? null : orgIdHeader);
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
        boolean globalAdmin = isGlobalAdmin(jwt);
        boolean orgOperator = isOrgOperator(jwt);
        if (!globalAdmin && orgOperator && orgIdHeader == null) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        BookingResponse updated = service.cancelBooking(
                jwt.getSubject(),
                globalAdmin || orgOperator,
                bookingId,
                ifMatch,
                request,
                globalAdmin ? null : orgIdHeader);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.CANCELLED, updated, httpRequest));
    }

    public record AssignRequest(@NotNull UUID technicianId) {}

    @PostMapping("/{bookingId}/assign")
    public ResponseEntity<?> assign(@PathVariable UUID bookingId, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) UUID organizationId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody AssignRequest input, HttpServletRequest request) {
        if (!isGlobalAdmin(jwt) && !SupabaseJwtConverterSupport.hasAnyRoleOrPermission(jwt,
                new String[]{RbacConstants.ROLE_ORGANIZATION}, RbacConstants.PERM_BOOKING_DISPATCH)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        var result = service.assignTechnician(bookingId, input.technicianId(), jwt.getSubject(), isGlobalAdmin(jwt), organizationId, ifMatch);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(result.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, result, request));
    }

    @PostMapping("/{bookingId}/work-updates")
    public ResponseEntity<ApiResponse<BookingResponse>> addWorkUpdate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) UUID orgIdHeader,
            @PathVariable UUID bookingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CreateWorkUpdateRequest request,
            HttpServletRequest httpRequest) {
        if (!isWorkUpdater(jwt)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        boolean globalAdmin = isGlobalAdmin(jwt);
        boolean orgOperator = isOrgOperator(jwt);
        if (!globalAdmin && orgOperator && orgIdHeader == null) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        BookingResponse updated = service.appendWorkUpdate(
                jwt.getSubject(),
                globalAdmin || orgOperator,
                globalAdmin ? null : orgIdHeader,
                bookingId,
                ifMatch,
                request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    private boolean isGlobalAdmin(Jwt jwt) {
        return SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                jwt, new String[]{RbacConstants.ROLE_ADMIN}, RbacConstants.PERM_ADMIN_ALL);
    }

    private boolean isOrgOperator(Jwt jwt) {
        return SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                jwt,
                new String[]{RbacConstants.ROLE_ORGANIZATION},
                RbacConstants.PERM_BOOKING_DISPATCH, RbacConstants.PERM_BOOKING_READ);
    }

    private boolean isTechnician(Jwt jwt) {
        return SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                jwt,
                new String[]{RbacConstants.ROLE_TECHNICIAN},
                RbacConstants.PERM_BOOKING_UPDATE_STATUS);
    }

    private boolean isWorkUpdater(Jwt jwt) {
        return SupabaseJwtConverterSupport.hasAnyRoleOrPermission(
                jwt,
                new String[]{RbacConstants.ROLE_TECHNICIAN, RbacConstants.ROLE_ORGANIZATION, RbacConstants.ROLE_ADMIN},
                RbacConstants.PERM_BOOKING_UPDATE_STATUS, RbacConstants.PERM_BOOKING_DISPATCH, RbacConstants.PERM_ADMIN_ALL);
    }
}
