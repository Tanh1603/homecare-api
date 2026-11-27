package com.homecare.booking.controller;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.ApproveExtraCostRequest;
import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.CancelBookingRequest;
import com.homecare.booking.dto.BookingDtos.ConfirmRescheduleRequest;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateWorkUpdateRequest;
import com.homecare.booking.dto.BookingDtos.ReportNoShowRequest;
import com.homecare.booking.dto.BookingDtos.RescheduleBookingRequest;
import com.homecare.booking.dto.BookingDtos.WorkEvidenceResponse;
import com.homecare.booking.service.BookingLifecycleService;
import com.homecare.booking.service.QuotationService;
import com.homecare.booking.service.QuotationService.AcceptQuotationInput;
import com.homecare.booking.service.QuotationService.CreateRevisedQuotationInput;
import com.homecare.booking.service.QuotationService.QuotationResponse;
import com.homecare.booking.service.QuotationService.RejectQuotationInput;
import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.booking.dto.BookingExecutionDtos.AcceptBookingRequest;
import com.homecare.booking.dto.BookingExecutionDtos.CustomerNoShowRequest;
import com.homecare.booking.dto.BookingExecutionDtos.RefuseRevisionRequest;
import com.homecare.booking.dto.BookingExecutionDtos.RequestRevisionRequest;
import com.homecare.booking.dto.BookingExecutionDtos.ResumeFromPartsRequest;
import com.homecare.booking.dto.BookingExecutionDtos.StartReworkRequest;
import com.homecare.booking.dto.BookingExecutionDtos.SubmitCompletionRequest;
import com.homecare.booking.dto.BookingExecutionDtos.WaitingForPartsRequest;
import com.homecare.booking.service.BookingAcceptanceService;
import com.homecare.booking.service.BookingExecutionService;
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
    private final BookingExecutionService executionService;
    private final BookingAcceptanceService acceptanceService;
    private final QuotationService quotationService;
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
        String requesterName = SupabaseJwtConverterSupport.extractDisplayName(jwt);
        BookingResponse created;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            created = idempotencyService.execute(
                    sub,
                    "POST:/api/v1/bookings",
                    idempotencyKey,
                    request.toString(),
                    () -> service.createBooking(sub, requesterName, request));
        } else {
            created = service.createBooking(sub, requesterName, request);
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

    public record AssignRequest(@NotNull UUID technicianId, String reason) {
        public AssignRequest(UUID technicianId) {
            this(technicianId, null);
        }
    }

    @PostMapping("/{bookingId}/assign")
    public ResponseEntity<ApiResponse<BookingResponse>> assign(@PathVariable UUID bookingId, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody AssignRequest input, HttpServletRequest request) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        String actorName = SupabaseJwtConverterSupport.extractDisplayName(jwt);
        var result = service.assignTechnician(
                actor, actorName, bookingId, input.technicianId(), input.reason(), ifMatch);
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

    @PostMapping(value = "/{bookingId}/work-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<WorkEvidenceResponse>> uploadWorkEvidence(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest httpRequest) throws IOException {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        WorkEvidenceResponse uploaded = executionService.uploadWorkEvidence(
                actor, bookingId, file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, uploaded, httpRequest));
    }

    @PostMapping("/{bookingId}/work-updates/{updateId}/approve-extra-cost")
    public ResponseEntity<ApiResponse<BookingResponse>> approveExtraCost(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            @PathVariable UUID updateId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ApproveExtraCostRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        BookingResponse updated = service.approveExtraCost(actor, bookingId, updateId, request, ifMatch);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    public record LinkDeviceRequest(@NotNull UUID deviceId) {}

    @PostMapping("/{bookingId}/link-device")
    public ResponseEntity<ApiResponse<BookingResponse>> linkDevice(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody LinkDeviceRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        BookingResponse updated = service.linkDeviceToAcceptedBooking(actor, bookingId, request.deviceId());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/reschedule-request")
    public ResponseEntity<ApiResponse<BookingResponse>> requestReschedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RescheduleBookingRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = service.requestReschedule(resolveActor(jwt, rawOrgId), bookingId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/reschedule-confirm")
    public ResponseEntity<ApiResponse<BookingResponse>> confirmReschedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ConfirmRescheduleRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = service.confirmReschedule(resolveActor(jwt, rawOrgId), bookingId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/report-no-show")
    public ResponseEntity<ApiResponse<BookingResponse>> reportTechnicianNoShow(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ReportNoShowRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        BookingResponse updated = service.reportTechnicianNoShow(actor, bookingId, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @GetMapping("/{bookingId}/revised-quotations")
    public ResponseEntity<ApiResponse<List<QuotationResponse>>> listRevisedQuotations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @PathVariable UUID bookingId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                quotationService.listRevisedQuotations(resolveActor(jwt, rawOrgId), bookingId),
                httpRequest));
    }

    @PostMapping("/{bookingId}/revised-quotations")
    public ResponseEntity<ApiResponse<QuotationResponse>> issueRevisedQuotation(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CreateRevisedQuotationInput input,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        QuotationResponse created = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyService.execute(
                        actor.userId(), "POST:/api/v1/bookings/" + bookingId + "/revised-quotations", idempotencyKey, input.toString(),
                        () -> quotationService.issueRevisedQuotation(actor, bookingId, input))
                : quotationService.issueRevisedQuotation(actor, bookingId, input);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(responseFactory.created(ApiMessages.CREATED, created, httpRequest));
    }

    @PostMapping("/{bookingId}/revised-quotations/{quoteId}/accept")
    public ResponseEntity<ApiResponse<QuotationResponse>> acceptRevisedQuotation(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID bookingId,
            @PathVariable UUID quoteId,
            @Valid @RequestBody AcceptQuotationInput input,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        QuotationResponse accepted = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyService.execute(
                        actor.userId(), "POST:/api/v1/bookings/" + bookingId + "/revised-quotations/" + quoteId + "/accept",
                        idempotencyKey, input.toString(),
                        () -> quotationService.acceptRevisedQuotation(actor.userId(), bookingId, quoteId, input))
                : quotationService.acceptRevisedQuotation(actor.userId(), bookingId, quoteId, input);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.UPDATED, accepted, httpRequest));
    }

    @PostMapping("/{bookingId}/revised-quotations/{quoteId}/reject")
    public ResponseEntity<ApiResponse<QuotationResponse>> rejectRevisedQuotation(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID bookingId,
            @PathVariable UUID quoteId,
            @Valid @RequestBody RejectQuotationInput input,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        QuotationResponse rejected = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyService.execute(
                        actor.userId(), "POST:/api/v1/bookings/" + bookingId + "/revised-quotations/" + quoteId + "/reject",
                        idempotencyKey, input.toString(),
                        () -> quotationService.rejectRevisedQuotation(actor.userId(), bookingId, quoteId, input))
                : quotationService.rejectRevisedQuotation(actor.userId(), bookingId, quoteId, input);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.UPDATED, rejected, httpRequest));
    }

    @PostMapping("/{bookingId}/waiting-for-parts")
    public ResponseEntity<ApiResponse<BookingResponse>> markWaitingForParts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @Valid @RequestBody WaitingForPartsRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = executionService.markWaitingForParts(resolveActor(jwt, rawOrgId), bookingId, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/resume-from-parts")
    public ResponseEntity<ApiResponse<BookingResponse>> resumeFromWaitingForParts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @RequestBody(required = false) ResumeFromPartsRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = executionService.resumeFromWaitingForParts(resolveActor(jwt, rawOrgId), bookingId, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/reassign-technician")
    public ResponseEntity<ApiResponse<BookingResponse>> reassignSameOrgTechnician(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @Valid @RequestBody ResumeFromPartsRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = executionService.reassignSameOrgTechnician(resolveActor(jwt, rawOrgId), bookingId, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/customer-no-show")
    public ResponseEntity<ApiResponse<BookingResponse>> reportCustomerNoShow(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CustomerNoShowRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = executionService.reportCustomerNoShow(resolveActor(jwt, rawOrgId), bookingId, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/complete")
    public ResponseEntity<ApiResponse<BookingResponse>> submitCompletionForAcceptance(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @Valid @RequestBody SubmitCompletionRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = executionService.submitCompletionForAcceptance(resolveActor(jwt, rawOrgId), bookingId, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/accept")
    public ResponseEntity<ApiResponse<BookingResponse>> acceptBooking(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID bookingId,
            @RequestBody(required = false) AcceptBookingRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        BookingResponse updated = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyService.execute(
                        actor.userId(), "POST:/api/v1/bookings/" + bookingId + "/accept",
                        idempotencyKey, String.valueOf(request),
                        () -> acceptanceService.acceptBooking(actor, bookingId, ifMatch, request))
                : acceptanceService.acceptBooking(actor, bookingId, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/request-revision")
    public ResponseEntity<ApiResponse<BookingResponse>> requestRevision(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID bookingId,
            @Valid @RequestBody RequestRevisionRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        BookingResponse updated = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyService.execute(
                        actor.userId(), "POST:/api/v1/bookings/" + bookingId + "/request-revision",
                        idempotencyKey, request.toString(),
                        () -> acceptanceService.requestRevision(actor, bookingId, ifMatch, request))
                : acceptanceService.requestRevision(actor, bookingId, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/start-rework")
    public ResponseEntity<ApiResponse<BookingResponse>> startRework(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @RequestBody(required = false) StartReworkRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = acceptanceService.startRework(resolveActor(jwt, rawOrgId), bookingId, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @PostMapping("/{bookingId}/refuse-revision")
    public ResponseEntity<ApiResponse<BookingResponse>> refuseRevision(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @PathVariable UUID bookingId,
            @Valid @RequestBody RefuseRevisionRequest request,
            HttpServletRequest httpRequest) {
        BookingResponse updated = acceptanceService.refuseRevision(resolveActor(jwt, rawOrgId), bookingId, ifMatch, request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, EtagSupport.format(updated.version()))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        UUID orgId = ActorContextResolver.parseOrganizationId(rawOrgId);
        return ActorContextResolver.resolve(jwt, orgId, providerGrpcClient);
    }
}
