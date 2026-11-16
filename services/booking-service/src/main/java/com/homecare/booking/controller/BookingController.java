package com.homecare.booking.controller;

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

import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.CancelBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateWorkUpdateRequest;
import com.homecare.booking.service.BookingLifecycleService;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/bookings")
@Validated
@RequiredArgsConstructor
public class BookingController {

    private final BookingLifecycleService service;
    private final ProviderGrpcClientAdapter providerGrpcClient;
    private final IdempotencyService idempotencyService;
    private final ServletApiResponseFactory responseFactory;

    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request,
            HttpServletRequest httpRequest) {
        String sub = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId))
                .userId();
        BookingResponse created;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            created = idempotencyService.execute(
                    sub,
                    "POST:/api/v1/bookings",
                    idempotencyKey,
                    request.toString(),
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
            @RequestParam(required = false) String scope,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE_STRING) int size,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        PageResponse<BookingResponse> result = service.listBookingsPage(actor, page, size, status, scope);
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                result,
                httpRequest));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<BookingResponse>> getBooking(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        BookingResponse booking = service.getBooking(actor, bookingId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(booking.version()))
                .body(responseFactory.ok(ApiMessages.RETRIEVED, booking, httpRequest));
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<ApiResponse<BookingResponse>> cancelBooking(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) CancelBookingRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        BookingResponse updated = service.cancelBooking(actor, bookingId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.CANCELLED, updated, httpRequest));
    }

    public record AssignRequest(@NotNull UUID technicianId) {
    }

    @PostMapping("/{bookingId}/assign")
    public ResponseEntity<?> assign(@PathVariable UUID bookingId, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody AssignRequest input, HttpServletRequest request) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        var result = service.assignTechnician(actor, bookingId, input.technicianId(), ifMatch);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(result.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, result, request));
    }

    @PostMapping("/{bookingId}/work-updates")
    public ResponseEntity<ApiResponse<BookingResponse>> addWorkUpdate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CreateWorkUpdateRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        BookingResponse updated = service.appendWorkUpdate(actor, bookingId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        UUID orgId = ActorContextResolver.parseOrganizationId(rawOrgId);
        return ActorContextResolver.resolve(jwt, orgId, providerGrpcClient);
    }
}
