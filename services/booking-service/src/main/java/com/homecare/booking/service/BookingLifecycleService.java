package com.homecare.booking.service;

import lombok.RequiredArgsConstructor;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.HouseholdVerificationClient.WarrantyVerificationResult;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.BookingHistoryResponse;
import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.CancelBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateWorkUpdateRequest;
import com.homecare.booking.dto.BookingDtos.VerifyBookingReviewEligibilityResponse;
import com.homecare.booking.dto.BookingDtos.WorkUpdateResponse;
import com.homecare.booking.entity.BookingAllocationSagaEntity;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingHistoryEntry;
import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.entity.WorkUpdateEntity;
import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.booking.repository.BookingAllocationSagaRepository;
import com.homecare.booking.repository.BookingHistoryRepository;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.WorkUpdateRepository;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class BookingLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(BookingLifecycleService.class);
    public static final String EXCHANGE = DomainEventContracts.EXCHANGE;

    private final BookingRepository bookingRepository;
    private final BookingHistoryRepository historyRepository;
    private final WorkUpdateRepository workUpdateRepository;
    private final BookingAllocationSagaRepository sagaRepository;
    private final BookingOutboxEventRepository outboxEventRepository;
    private final ProviderGrpcClientAdapter providerGrpcClient;
    private final HouseholdVerificationClient householdVerificationClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public BookingResponse createBooking(String requesterProfileId, CreateBookingRequest request) {
        Instant now = Instant.now();
        Instant preferredStart = request.preferredStart() != null
                ? request.preferredStart()
                : now.plus(2, ChronoUnit.HOURS);
        if (request.preferredEnd() != null && !request.preferredEnd().isAfter(preferredStart)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "preferredEnd must be after preferredStart.");
        }

        boolean isWarrantyClaim = request.warrantyId() != null
                || "WARRANTY_CLAIM".equalsIgnoreCase(request.bookingType());
        String bookingType = isWarrantyClaim ? "WARRANTY_CLAIM" : "STANDARD";

        UUID resolvedDeviceId = request.deviceId();
        String resolvedDeviceType;
        UUID warrantyOrganizationId = null;

        if (isWarrantyClaim) {
            WarrantyVerificationResult warrantyResult = householdVerificationClient.verifyWarranty(
                    request.warrantyId(), requesterProfileId);
            if (resolvedDeviceId == null) {
                resolvedDeviceId = warrantyResult.deviceId();
            }
            resolvedDeviceType = warrantyResult.deviceType() != null && !warrantyResult.deviceType().isBlank()
                    ? warrantyResult.deviceType()
                    : request.deviceType();
            warrantyOrganizationId = warrantyResult.organizationId();
        } else {
            resolvedDeviceType = householdVerificationClient.verifyDeviceOrFallback(
                    request.deviceId(), requesterProfileId, request.deviceType());
        }

        UUID bookingId = UUID.randomUUID();
        String intentId = "alloc-" + bookingId;

        VerifyOfferingResponse offering;
        try {
            offering = providerGrpcClient.verifyOffering(request.serviceId());
        } catch (Exception e) {
            log.warn("gRPC VerifyOffering failed for service {}: {}", request.serviceId(), e.getMessage());
            throw new DomainException(BookingErrorCode.SERVICE_NOT_AVAILABLE);
        }

        if (!offering.getActive()) {
            throw new DomainException(BookingErrorCode.SERVICE_NOT_AVAILABLE);
        }

        BookingAllocationSagaEntity saga = sagaRepository.save(BookingAllocationSagaEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .intentId(intentId)
                .sagaStatus("STARTED")
                .currentStep("VERIFY_OFFERING")
                .retryCount(0)
                .startedAt(now)
                .updatedAt(now)
                .build());

        int durationMins = offering.getEstimatedDurationMinutes() > 0
                ? offering.getEstimatedDurationMinutes()
                : 60;
        Instant preferredEnd = request.preferredEnd() != null
                ? request.preferredEnd()
                : preferredStart.plus(durationMins, ChronoUnit.MINUTES);
        if (!preferredEnd.isAfter(preferredStart)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "preferredEnd must be after preferredStart.");
        }
        String finalDeviceType = (resolvedDeviceType != null && !resolvedDeviceType.isBlank())
                ? resolvedDeviceType
                : offering.getCategory();

        UUID offeringProviderId = parseUuidOrNull(offering.getProviderId());
        UUID targetProviderId;
        boolean strictProviderMatch;
        long quotedPrice;

        if (isWarrantyClaim) {
            // Flow 1: In-Warranty Claim -> Free (0 VND) and strictly locked to the selling
            // store (organizationId)
            targetProviderId = warrantyOrganizationId != null ? warrantyOrganizationId : offeringProviderId;
            strictProviderMatch = true;
            quotedPrice = 0L;
        } else {
            // Flow 2 & 3: Standard Paid Booking -> Prioritize preferredProviderId /
            // offeringProviderId, allow fallback
            targetProviderId = request.preferredProviderId() != null
                    ? request.preferredProviderId()
                    : offeringProviderId;
            strictProviderMatch = false;
            quotedPrice = offering.getBasePrice();
        }

        UUID providerId = targetProviderId;
        saga.setSagaStatus("OFFERING_VERIFIED");
        saga.setCurrentStep("ALLOCATE_TECHNICIAN");
        saga.setProviderId(providerId);
        saga.setUpdatedAt(Instant.now());
        sagaRepository.save(saga);

        String status = "REQUESTED";
        String allocationStatus = "ALLOCATION_PENDING";
        UUID assignedTechId = null;
        String assignedTechProfileId = null;
        String assignedTechName = null;

        try {
            AllocateTechnicianResponse alloc = providerGrpcClient.allocateTechnician(
                    intentId,
                    bookingId,
                    request.serviceId(),
                    finalDeviceType,
                    preferredStart,
                    preferredEnd,
                    targetProviderId,
                    strictProviderMatch);
            if (alloc != null && alloc.getAllocated()) {
                status = "ASSIGNED";
                allocationStatus = "ALLOCATED";
                assignedTechId = parseUuidOrNull(alloc.getTechnicianId());
                assignedTechProfileId = alloc.getTechnicianProfileId();
                assignedTechName = alloc.getTechnicianName();
                providerId = parseUuidOrNull(alloc.getProviderId());
                saga.setSagaStatus("ALLOCATED");
                saga.setCurrentStep("COMPLETED");
                saga.setProviderId(providerId);
                saga.setAssignedTechnicianId(assignedTechId);
                saga.setUpdatedAt(Instant.now());
                saga.setCompletedAt(Instant.now());
                sagaRepository.save(saga);
            } else {
                allocationStatus = "ALLOCATION_FAILED";
                String reason = alloc != null ? alloc.getFailureReason() : "NO_AVAILABLE_TECHNICIAN";
                saga.setSagaStatus("COMPENSATING");
                saga.setCurrentStep("COMPENSATE_TO_REQUESTED");
                saga.setFailureReason(reason);
                saga.setCompensationReason("Graceful AP degradation to REQUESTED / ALLOCATION_FAILED");
                saga.setSagaStatus("FAILED");
                saga.setUpdatedAt(Instant.now());
                saga.setCompletedAt(Instant.now());
                sagaRepository.save(saga);
            }
        } catch (Exception e) {
            log.warn("gRPC AllocateTechnician degraded for booking {}: {}", bookingId, e.getMessage());
            allocationStatus = "ALLOCATION_FAILED";
            saga.setSagaStatus("FAILED");
            saga.setCurrentStep("COMPENSATE_TO_REQUESTED");
            saga.setFailureReason(e.getClass().getSimpleName());
            saga.setCompensationReason("Graceful AP degradation on gRPC timeout/error");
            saga.setUpdatedAt(Instant.now());
            saga.setCompletedAt(Instant.now());
            sagaRepository.save(saga);
        }

        BookingEntity saved = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(bookingId)
                .requesterProfileId(requesterProfileId)
                .serviceId(request.serviceId())
                .serviceName(offering.getName().isBlank() ? "Dịch vụ HomeCare" : offering.getName())
                .providerId(providerId)
                .preferredProviderId(request.preferredProviderId())
                .bookingType(bookingType)
                .warrantyId(request.warrantyId())
                .deviceId(resolvedDeviceId)
                .deviceType(finalDeviceType)
                .assignedTechnicianId(assignedTechId)
                .assignedTechnicianProfileId(assignedTechProfileId)
                .assignedTechnicianName(assignedTechName)
                .preferredStart(preferredStart)
                .preferredEnd(preferredEnd)
                .address(request.address().trim())
                .notes(request.notes())
                .status(status)
                .allocationStatus(allocationStatus)
                .quotedPrice(quotedPrice)
                .createdAt(now)
                .updatedAt(now)
                .build());

        recordHistory(bookingId, null, "REQUESTED", requesterProfileId, "Booking requested (" + bookingType + ")");
        if ("ASSIGNED".equals(status)) {
            recordHistory(
                    bookingId,
                    "REQUESTED",
                    "ASSIGNED",
                    "AI_DISPATCHER",
                    "AI Auto-Dispatch assigned technician " + assignedTechName);
            publishDomainEvent("booking.assigned.v1", saved);
        } else {
            publishDomainEvent("booking.requested.v1", saved);
        }

        return toBookingResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> listBookingsPage(String actorProfileId, boolean isGlobalAdmin,
            boolean isOrgOperator, boolean isTechnician, UUID orgScopeId, int page, int size, String status, String scope) {
        if (page < 1 || size < 1 || size > 100) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        if (status != null && !Set.of("REQUESTED", "ASSIGNED", "IN_PROGRESS", "COMPLETED", "CANCELLED").contains(status)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }
        String normalizedScope = scope != null && !scope.isBlank() ? scope.trim().toUpperCase() : null;
        if (normalizedScope != null && !Set.of("CUSTOMER", "TECHNICIAN").contains(normalizedScope)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }
        String requester = null;
        String technician = null;
        UUID provider = null;
        if (isGlobalAdmin && normalizedScope == null) {
            provider = orgScopeId;
        } else if (isOrgOperator && orgScopeId != null) {
            requireOrgMembership(orgScopeId, actorProfileId);
            provider = orgScopeId;
        } else if ("CUSTOMER".equals(normalizedScope)) {
            requester = actorProfileId;
        } else if ("TECHNICIAN".equals(normalizedScope)) {
            if (!isTechnician) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            technician = actorProfileId;
        } else if (isTechnician) {
            requester = actorProfileId;
            technician = actorProfileId;
        } else {
            requester = actorProfileId;
        }
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("createdAt").descending().and(Sort.by("id")));
        var result = bookingRepository.search(requester, technician, provider, status, pageable);
        List<BookingEntity> content = result.getContent();
        if (content.isEmpty()) {
            return PageResponse.of(List.of(), page, size, result.getTotalElements());
        }
        List<UUID> ids = content.stream().map(BookingEntity::getId).toList();
        var historyByBooking = historyRepository.findByBookingIdInOrderByOccurredAtAsc(ids).stream()
                .collect(java.util.stream.Collectors.groupingBy(BookingHistoryEntry::getBookingId));
        var updatesByBooking = workUpdateRepository.findByBookingIdInOrderByCreatedAtAsc(ids).stream()
                .collect(java.util.stream.Collectors.groupingBy(WorkUpdateEntity::getBookingId));
        var items = content.stream()
                .map(b -> toBookingResponse(b,
                        historyByBooking.getOrDefault(b.getId(), List.of()),
                        updatesByBooking.getOrDefault(b.getId(), List.of())))
                .toList();
        return PageResponse.of(items, page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(String actorProfileId, boolean isTechOrAdmin, UUID bookingId) {
        return getBooking(actorProfileId, isTechOrAdmin, bookingId, null);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(String actorProfileId, boolean isTechOrAdmin, UUID bookingId, UUID orgScopeId) {
        return getBooking(actorProfileId, false, isTechOrAdmin && orgScopeId != null, isTechOrAdmin && orgScopeId == null, bookingId, orgScopeId);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(
            String actorProfileId,
            boolean isGlobalAdmin,
            boolean isOrgOperator,
            boolean isTechnician,
            UUID bookingId,
            UUID orgScopeId) {
        BookingEntity booking = requireBooking(bookingId);
        if (isGlobalAdmin) {
            return toBookingResponse(booking);
        }
        if (orgScopeId == null && booking.getRequesterProfileId().equals(actorProfileId)) {
            return toBookingResponse(booking);
        }
        if (isOrgOperator && orgScopeId != null) {
            requireOrgMembership(orgScopeId, actorProfileId);
            if (!orgScopeId.equals(booking.getProviderId())) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            return toBookingResponse(booking);
        }
        if (isTechnician) {
            boolean matchesOrg = false;
            if (orgScopeId != null) {
                requireOrgMembership(orgScopeId, actorProfileId);
                matchesOrg = orgScopeId.equals(booking.getProviderId());
            }
            boolean matchesTech = actorProfileId.equals(booking.getAssignedTechnicianProfileId());
            if (matchesOrg || matchesTech) {
                return toBookingResponse(booking);
            }
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    @Transactional
    public BookingResponse cancelBooking(
            String actorProfileId,
            boolean isAdmin,
            UUID bookingId,
            String ifMatchHeader,
            CancelBookingRequest request) {
        return cancelBooking(actorProfileId, isAdmin, bookingId, ifMatchHeader, request, null);
    }

    @Transactional
    public BookingResponse cancelBooking(
            String actorProfileId,
            boolean isAdmin,
            UUID bookingId,
            String ifMatchHeader,
            CancelBookingRequest request,
            UUID orgScopeId) {
        BookingEntity booking = requireBooking(bookingId);
        if (orgScopeId != null) {
            if (!isAdmin) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            requireOrgMembership(orgScopeId, actorProfileId);
            if (!orgScopeId.equals(booking.getProviderId())) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
        } else if (!isAdmin && !booking.getRequesterProfileId().equals(actorProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        EtagSupport.verifyIfMatch(ifMatchHeader, booking.getVersion());
        if ("IN_PROGRESS".equals(booking.getStatus())
                || "COMPLETED".equals(booking.getStatus())
                || "CANCELLED".equals(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE);
        }
        String previous = booking.getStatus();
        booking.setStatus("CANCELLED");
        booking.setUpdatedAt(Instant.now());
        BookingEntity saved = bookingRepository.saveAndFlush(booking);
        recordHistory(
                bookingId,
                previous,
                "CANCELLED",
                actorProfileId,
                request != null && request.reason() != null ? request.reason() : "Customer cancelled");
        publishDomainEvent("booking.cancelled.v1", saved);
        return toBookingResponse(saved);
    }

    @Transactional
    public BookingResponse appendWorkUpdate(
            String technicianProfileId,
            UUID bookingId,
            String ifMatchHeader,
            CreateWorkUpdateRequest request) {
        return appendWorkUpdate(technicianProfileId, false, null, bookingId, ifMatchHeader, request);
    }

    @Transactional
    public BookingResponse appendWorkUpdate(
            String actorProfileId,
            boolean isOrgOrAdmin,
            UUID orgScopeId,
            UUID bookingId,
            String ifMatchHeader,
            CreateWorkUpdateRequest request) {
        BookingEntity booking = requireBooking(bookingId);
        if (isOrgOrAdmin) {
            if (orgScopeId != null) {
                requireOrgMembership(orgScopeId, actorProfileId);
                if (!orgScopeId.equals(booking.getProviderId())) {
                    throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
                }
            }
        } else if (!actorProfileId.equals(booking.getAssignedTechnicianProfileId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        EtagSupport.verifyIfMatch(ifMatchHeader, booking.getVersion());
        String kind = request.kind().trim().toUpperCase();
        String previous = booking.getStatus();

        switch (kind) {
            case "ARRIVED", "DIAGNOSED", "STARTED", "IN_PROGRESS" -> {
                if (!"ASSIGNED".equals(previous) && !"IN_PROGRESS".equals(previous)) {
                    throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE);
                }
                booking.setStatus("IN_PROGRESS");
                recordHistory(bookingId, previous, "IN_PROGRESS", actorProfileId,
                        "[" + kind + "] " + (request.note() != null ? request.note() : ""));
            }
            case "NOTE" -> {
                if (!"ASSIGNED".equals(previous) && !"IN_PROGRESS".equals(previous)) {
                    throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE);
                }
            }
            case "COMPLETED" -> {
                if (!"IN_PROGRESS".equals(previous)) {
                    throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE);
                }
                booking.setStatus("COMPLETED");
                recordHistory(bookingId, previous, "COMPLETED", actorProfileId, request.note());
            }
            default -> throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }

        workUpdateRepository.save(WorkUpdateEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .technicianProfileId(actorProfileId)
                .kind(kind)
                .note(request.note())
                .createdAt(Instant.now())
                .build());

        booking.setUpdatedAt(Instant.now());
        BookingEntity saved = bookingRepository.saveAndFlush(booking);

        if ("COMPLETED".equals(saved.getStatus())) {
            publishDomainEvent("booking.completed.v1", saved);
        }
        return toBookingResponse(saved);
    }

    @Transactional(readOnly = true)
    public VerifyBookingReviewEligibilityResponse verifyReviewEligibility(
            UUID bookingId,
            String requesterProfileId) {
        return bookingRepository.findById(bookingId)
                .filter(b -> b.getRequesterProfileId().equals(requesterProfileId)
                        && "COMPLETED".equals(b.getStatus()))
                .map(b -> new VerifyBookingReviewEligibilityResponse(
                        true,
                        b.getId(),
                        b.getProviderId(),
                        b.getServiceId(),
                        b.getStatus()))
                .orElseGet(() -> new VerifyBookingReviewEligibilityResponse(
                        false,
                        bookingId,
                        null,
                        null,
                        "NOT_ELIGIBLE"));
    }

    private void requireOrgMembership(UUID orgScopeId, String actorProfileId) {
        if (orgScopeId == null || actorProfileId == null
                || !providerGrpcClient.verifyOrganizationMembership(orgScopeId, actorProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
    }

    private void recordHistory(UUID bookingId, String from, String to, String actorId, String reason) {
        historyRepository.save(BookingHistoryEntry.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .fromStatus(from)
                .toStatus(to)
                .actorId(actorId)
                .reason(reason)
                .occurredAt(Instant.now())
                .build());
    }

    @Transactional
    public BookingResponse assignTechnician(UUID bookingId, UUID technicianId, String actor, boolean admin,
            UUID organizationId, String ifMatch) {
        if (!admin) {
            requireOrgMembership(organizationId, actor);
        }
        var booking = bookingRepository.lockById(bookingId).orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        if (!admin && booking.getProviderId() != null && !organizationId.equals(booking.getProviderId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!"REQUESTED".equals(booking.getStatus())) throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE);
        UUID targetProviderId = booking.getProviderId() != null ? booking.getProviderId() : organizationId;
        var result = providerGrpcClient.assignTechnician(bookingId, booking.getServiceId(), booking.getDeviceType(),
                booking.getPreferredStart(), booking.getPreferredEnd(), targetProviderId, technicianId);
        if (!result.getAllocated() || !technicianId.toString().equals(result.getTechnicianId())
                || (targetProviderId != null && !targetProviderId.toString().equals(result.getProviderId()))) throw new DomainException(CommonErrorCode.CONFLICT);
        booking.setProviderId(parseUuidOrNull(result.getProviderId()));
        booking.setAssignedTechnicianId(technicianId); booking.setAssignedTechnicianProfileId(result.getTechnicianProfileId());
        booking.setAssignedTechnicianName(result.getTechnicianName()); booking.setStatus("ASSIGNED");
        booking.setAllocationStatus("ALLOCATED"); booking.setUpdatedAt(Instant.now());
        var saved = bookingRepository.saveAndFlush(booking);
        sagaRepository.findByBookingId(bookingId).ifPresent(saga -> {
            saga.setSagaStatus("ALLOCATED"); saga.setCurrentStep("COMPLETED"); saga.setAssignedTechnicianId(technicianId);
            saga.setFailureReason(null); saga.setCompletedAt(Instant.now()); saga.setUpdatedAt(Instant.now()); sagaRepository.save(saga);
        });
        recordHistory(bookingId, "REQUESTED", "ASSIGNED", actor, "Manually assigned technician");
        publishDomainEvent("booking.assigned.v1", saved);
        return toBookingResponse(saved);
    }

    private void publishDomainEvent(String eventType, BookingEntity booking) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookingId", booking.getId());
        data.put("requesterProfileId", booking.getRequesterProfileId());
        data.put("technicianProfileId", booking.getAssignedTechnicianProfileId());
        data.put("serviceName", booking.getServiceName());
        data.put("status", booking.getStatus());
        data.put("bookingType", booking.getBookingType());
        data.put("deviceId", booking.getDeviceId());
        data.put("warrantyId", booking.getWarrantyId());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId);
        envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, eventType);
        envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
        envelope.put(DomainEventContracts.FIELD_PRODUCER, "booking-service");
        envelope.put("correlationId", booking.getId());
        envelope.put(DomainEventContracts.FIELD_DATA, data);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize booking outbox payload", e);
        }

        outboxEventRepository.save(BookingOutboxEventEntity.builder()
                .id(eventId)
                .aggregateType("Booking")
                .aggregateId(booking.getId())
                .eventType(eventType)
                .routingKey(eventType)
                .payloadJson(payload)
                .status(EntityStatus.PENDING.name())
                .retryCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
    }

    private BookingEntity requireBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
    }

    private UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private BookingResponse toBookingResponse(BookingEntity b) {
        return toBookingResponse(
                b,
                historyRepository.findByBookingIdOrderByOccurredAtAsc(b.getId()),
                workUpdateRepository.findByBookingIdOrderByCreatedAtAsc(b.getId()));
    }

    private BookingResponse toBookingResponse(
            BookingEntity b,
            List<BookingHistoryEntry> historyEntities,
            List<WorkUpdateEntity> updateEntities) {
        List<BookingHistoryResponse> history = historyEntities
                .stream()
                .map(h -> new BookingHistoryResponse(
                        h.getId(), h.getFromStatus(), h.getToStatus(), h.getActorId(), h.getReason(),
                        h.getOccurredAt()))
                .toList();
        List<WorkUpdateResponse> updates = updateEntities
                .stream()
                .map(u -> new WorkUpdateResponse(
                        u.getId(), u.getBookingId(), u.getTechnicianProfileId(), u.getKind(), u.getNote(),
                        u.getCreatedAt()))
                .toList();
        return new BookingResponse(
                b.getId(),
                b.getRequesterProfileId(),
                b.getServiceId(),
                b.getServiceName(),
                b.getProviderId(),
                b.getBookingType(),
                b.getWarrantyId(),
                b.getDeviceId(),
                b.getDeviceType(),
                b.getAssignedTechnicianId(),
                b.getAssignedTechnicianProfileId(),
                b.getAssignedTechnicianName(),
                b.getPreferredStart(),
                b.getPreferredEnd(),
                b.getAddress(),
                b.getNotes(),
                b.getStatus(),
                b.getAllocationStatus(),
                b.getQuotedPrice(),
                b.getVersion(),
                b.getCreatedAt(),
                b.getUpdatedAt(),
                history,
                updates);
    }
}
