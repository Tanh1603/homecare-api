package com.homecare.booking.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.homecare.booking.entity.AssignmentAttemptEntity;
import com.homecare.booking.entity.BookingAllocationSagaEntity;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.entity.BookingHistoryEntry;
import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.entity.WorkUpdateEntity;
import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.booking.repository.AssignmentAttemptRepository;
import com.homecare.booking.repository.BookingAllocationSagaRepository;
import com.homecare.booking.repository.BookingHistoryRepository;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.WorkUpdateRepository;
import com.homecare.booking.security.BookingAccessPolicy;
import com.homecare.booking.security.BookingReadScope;
import com.homecare.booking.security.BookingSpecifications;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingResponse;

import lombok.RequiredArgsConstructor;
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
    private final AssignmentAttemptRepository attemptRepository;
    private final BookingOutboxEventRepository outboxEventRepository;
    private final ProviderGrpcClientAdapter providerGrpcClient;
    private final HouseholdVerificationClient householdVerificationClient;
    private final BookingAccessPolicy accessPolicy;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    // Never add @Transactional here: DB locks and connections must NOT be held across gRPC calls.
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
        UUID operationId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        UUID finalDeviceId = resolvedDeviceId;
        String serviceName = offering.getName().isBlank() ? "Dịch vụ HomeCare" : offering.getName();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(txStatus -> {
            sagaRepository.save(BookingAllocationSagaEntity.builder()
                    .id(UUID.randomUUID())
                    .bookingId(bookingId)
                    .intentId(intentId)
                    .sagaStatus("OFFERING_VERIFIED")
                    .currentStep("ALLOCATE_TECHNICIAN")
                    .providerId(providerId)
                    .retryCount(0)
                    .startedAt(now)
                    .updatedAt(now)
                    .build());

            bookingRepository.saveAndFlush(BookingEntity.builder()
                    .id(bookingId)
                    .requesterProfileId(requesterProfileId)
                    .serviceId(request.serviceId())
                    .serviceName(serviceName)
                    .providerId(providerId)
                    .preferredProviderId(request.preferredProviderId())
                    .bookingType(bookingType)
                    .warrantyId(request.warrantyId())
                    .deviceId(finalDeviceId)
                    .deviceType(finalDeviceType)
                    .preferredStart(preferredStart)
                    .preferredEnd(preferredEnd)
                    .address(request.address().trim())
                    .notes(request.notes())
                    .status("REQUESTED")
                    .allocationStatus("ALLOCATING")
                    .quotedPrice(quotedPrice)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());

            attemptRepository.saveAndFlush(AssignmentAttemptEntity.builder()
                    .operationId(operationId)
                    .bookingId(bookingId)
                    .serviceId(request.serviceId())
                    .deviceType(finalDeviceType)
                    .preferredStart(preferredStart)
                    .preferredEnd(preferredEnd)
                    .providerId(targetProviderId)
                    .strictProviderMatch(strictProviderMatch)
                    .technicianId(null)
                    .status("ALLOCATING")
                    .claimToken(claimToken)
                    .leaseUntil(now.plusSeconds(15))
                    .retryCount(0)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());

            recordHistory(bookingId, null, "REQUESTED", requesterProfileId, "Booking requested (" + bookingType + ")");
        });

        AllocateTechnicianResponse alloc = null;
        Exception allocError = null;
        try {
            alloc = providerGrpcClient.allocateTechnician(
                    operationId,
                    intentId,
                    bookingId,
                    request.serviceId(),
                    finalDeviceType,
                    preferredStart,
                    preferredEnd,
                    targetProviderId,
                    strictProviderMatch);
        } catch (Exception e) {
            log.warn("gRPC AllocateTechnician ambiguous/timeout for booking {}: {}", bookingId, e.getMessage());
            allocError = e;
        }

        final AllocateTechnicianResponse finalAlloc = alloc;
        final Exception finalAllocError = allocError;
        try {
            return tx.execute(txStatus -> {
                Instant finishTime = Instant.now();
                BookingEntity booking = bookingRepository.lockById(bookingId)
                        .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
                BookingAllocationSagaEntity saga = sagaRepository.findByBookingId(bookingId).orElseThrow();

                if (finalAllocError != null) {
                    int fenced = attemptRepository.scheduleRetryWithFence(
                            operationId, claimToken, "ALLOCATING", "RECONCILIATION_PENDING",
                            finishTime.plusSeconds(5), finalAllocError.getMessage(), finishTime);
                    if (fenced == 1) {
                        booking.setAllocationStatus("RECONCILIATION_PENDING");
                        booking.setUpdatedAt(finishTime);
                        booking = bookingRepository.saveAndFlush(booking);
                    }
                    saga.setSagaStatus("RECONCILIATION_PENDING");
                    saga.setCurrentStep("RECONCILE_ALLOCATION");
                    saga.setFailureReason(finalAllocError.getClass().getSimpleName());
                    saga.setCompensationReason("Ambiguous gRPC timeout/error; queued for reconciliation");
                    saga.setUpdatedAt(finishTime);
                    sagaRepository.save(saga);
                    publishDomainEvent("booking.requested.v1", booking);
                    return toBookingResponse(booking);
                }

                if (finalAlloc != null && finalAlloc.getAllocated()) {
                    int fenced = attemptRepository.transitionWithFence(
                            operationId, claimToken, "ALLOCATING", "ALLOCATED", null, finishTime);
                    if (fenced == 0) {
                        throw new DomainException(CommonErrorCode.CONFLICT);
                    }
                    UUID assignedTechId = parseUuidOrNull(finalAlloc.getTechnicianId());
                    UUID actualProviderId = parseUuidOrNull(finalAlloc.getProviderId());
                    booking.setStatus("ASSIGNED");
                    booking.setAllocationStatus("ALLOCATED");
                    booking.setProviderId(actualProviderId);
                    booking.setAssignedTechnicianId(assignedTechId);
                    booking.setAssignedTechnicianProfileId(finalAlloc.getTechnicianProfileId());
                    booking.setAssignedTechnicianName(finalAlloc.getTechnicianName());
                    booking.setUpdatedAt(finishTime);
                    booking = bookingRepository.saveAndFlush(booking);

                    saga.setSagaStatus("ALLOCATED");
                    saga.setCurrentStep("COMPLETED");
                    saga.setProviderId(actualProviderId);
                    saga.setAssignedTechnicianId(assignedTechId);
                    saga.setUpdatedAt(finishTime);
                    saga.setCompletedAt(finishTime);
                    sagaRepository.save(saga);

                    recordHistory(bookingId, "REQUESTED", "ASSIGNED", "AI_DISPATCHER",
                            "AI Auto-Dispatch assigned technician " + finalAlloc.getTechnicianName());
                    publishDomainEvent("booking.assigned.v1", booking);
                    return toBookingResponse(booking);
                }

                String reason = finalAlloc != null ? finalAlloc.getFailureReason() : "NO_AVAILABLE_TECHNICIAN";
                int fenced = attemptRepository.transitionWithFence(
                        operationId, claimToken, "ALLOCATING", "FAILED", reason, finishTime);
                if (fenced == 1) {
                    booking.setAllocationStatus("UNALLOCATED");
                    booking.setUpdatedAt(finishTime);
                    booking = bookingRepository.saveAndFlush(booking);
                }
                saga.setCurrentStep("COMPENSATE_TO_REQUESTED");
                saga.setFailureReason(reason);
                saga.setCompensationReason("No technician available; booking kept REQUESTED / UNALLOCATED");
                saga.setSagaStatus("FAILED");
                saga.setUpdatedAt(finishTime);
                saga.setCompletedAt(finishTime);
                sagaRepository.save(saga);
                publishDomainEvent("booking.requested.v1", booking);
                return toBookingResponse(booking);
            });
        } catch (RuntimeException ex) {
            if (finalAlloc != null && finalAlloc.getAllocated()) {
                compensateAllocation(tx, bookingId, operationId, claimToken, "ALLOCATING", ex.getMessage());
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> listBookingsPage(
            ActorContext actor, int page, int size, String status, String scope) {
        if (page < 1 || size < 1 || size > 100)
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        if (status != null) {
            try {
                BookingStatus.valueOf(status);
            } catch (IllegalArgumentException ex) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
            }
        }
        BookingReadScope readScope = accessPolicy.readScope(actor, scope);
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("createdAt").descending().and(Sort.by("id")));
        var result = bookingRepository.findAll(BookingSpecifications.withScope(readScope, status), pageable);
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
    public BookingResponse getBooking(ActorContext actor, UUID bookingId) {
        BookingEntity booking = requireBooking(bookingId);
        if (!accessPolicy.isVisible(actor, booking)) {
            throw new DomainException(BookingErrorCode.BOOKING_NOT_FOUND);
        }
        return toBookingResponse(booking);
    }

    @Transactional
    public BookingResponse cancelBooking(
            ActorContext actor,
            UUID bookingId,
            String ifMatchHeader,
            CancelBookingRequest request) {
        BookingEntity booking = requireBooking(bookingId);
        accessPolicy.assertCanCancel(actor, booking);
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
                actor.userId(),
                request != null && request.reason() != null ? request.reason() : "Customer cancelled");
        publishDomainEvent("booking.cancelled.v1", saved);
        return toBookingResponse(saved);
    }

    @Transactional
    public BookingResponse appendWorkUpdate(
            ActorContext actor,
            UUID bookingId,
            String ifMatchHeader,
            CreateWorkUpdateRequest request) {
        BookingEntity booking = requireBooking(bookingId);
        accessPolicy.assertCanAppendWorkUpdate(actor, booking);
        EtagSupport.verifyIfMatch(ifMatchHeader, booking.getVersion());
        String kind = request.kind().trim().toUpperCase();
        String previous = booking.getStatus();

        switch (kind) {
            case "ARRIVED", "DIAGNOSED", "STARTED", "IN_PROGRESS" -> {
                if (!"ASSIGNED".equals(previous) && !"IN_PROGRESS".equals(previous)) {
                    throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE);
                }
                booking.setStatus("IN_PROGRESS");
                recordHistory(bookingId, previous, "IN_PROGRESS", actor.userId(),
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
                recordHistory(bookingId, previous, "COMPLETED", actor.userId(), request.note());
            }
            default -> throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }

        workUpdateRepository.save(WorkUpdateEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .technicianProfileId(actor.userId())
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

    private record ClaimedAssignment(
            UUID operationId,
            UUID claimToken,
            UUID serviceId,
            String deviceType,
            Instant preferredStart,
            Instant preferredEnd,
            UUID targetProviderId) {}

    // Never add @Transactional here: DB locks must NOT be held across gRPC calls.
    public BookingResponse assignTechnician(ActorContext actor, UUID bookingId, UUID technicianId, String ifMatch) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        ClaimedAssignment claim = tx.execute(status -> {
            var booking = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
            accessPolicy.assertCanAssign(actor, booking);
            EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
            if (!"REQUESTED".equals(booking.getStatus()) || !"UNALLOCATED".equals(booking.getAllocationStatus())) {
                throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE);
            }
            Instant now = Instant.now();
            int claimed = bookingRepository.tryClaimForAllocation(bookingId, now);
            if (claimed == 0) {
                throw new DomainException(CommonErrorCode.CONFLICT);
            }
            UUID targetProviderId = booking.getProviderId() != null
                    ? booking.getProviderId()
                    : (actor.mode() == AccessMode.ORGANIZATION ? actor.organizationId() : null);
            UUID operationId = UUID.randomUUID();
            UUID claimToken = UUID.randomUUID();
            attemptRepository.saveAndFlush(AssignmentAttemptEntity.builder()
                    .operationId(operationId)
                    .bookingId(bookingId)
                    .serviceId(booking.getServiceId())
                    .deviceType(booking.getDeviceType())
                    .preferredStart(booking.getPreferredStart())
                    .preferredEnd(booking.getPreferredEnd())
                    .providerId(targetProviderId)
                    .strictProviderMatch(targetProviderId != null)
                    .technicianId(technicianId)
                    .status("ALLOCATING")
                    .claimToken(claimToken)
                    .leaseUntil(now.plusSeconds(15))
                    .retryCount(0)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            return new ClaimedAssignment(
                    operationId, claimToken, booking.getServiceId(), booking.getDeviceType(),
                    booking.getPreferredStart(), booking.getPreferredEnd(), targetProviderId);
        });

        AllocateTechnicianResponse result;
        try {
            result = providerGrpcClient.assignTechnician(
                    claim.operationId(), bookingId, claim.serviceId(), claim.deviceType(),
                    claim.preferredStart(), claim.preferredEnd(), claim.targetProviderId(), technicianId);
        } catch (Exception ex) {
            tx.executeWithoutResult(status -> {
                Instant now = Instant.now();
                int fenced = attemptRepository.scheduleRetryWithFence(
                        claim.operationId(), claim.claimToken(), "ALLOCATING", "RECONCILIATION_PENDING",
                        now.plusSeconds(5), ex.getMessage(), now);
                if (fenced == 1) {
                    bookingRepository.findById(bookingId).ifPresent(b -> {
                        if ("ALLOCATING".equals(b.getAllocationStatus())) {
                            b.setAllocationStatus("RECONCILIATION_PENDING");
                            b.setUpdatedAt(now);
                            bookingRepository.save(b);
                        }
                    });
                }
            });
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ALLOCATION_UNAVAILABLE", "Technician allocation timed out; reconciliation pending.");
        }

        if (result == null || !result.getAllocated()) {
            tx.executeWithoutResult(status -> {
                Instant now = Instant.now();
                String reason = result != null ? result.getFailureReason() : "ALLOCATION_FAILED";
                int fenced = attemptRepository.transitionWithFence(
                        claim.operationId(), claim.claimToken(), "ALLOCATING", "FAILED", reason, now);
                if (fenced == 1) {
                    bookingRepository.findById(bookingId).ifPresent(b -> {
                        if ("ALLOCATING".equals(b.getAllocationStatus())) {
                            b.setAllocationStatus("UNALLOCATED");
                            b.setUpdatedAt(now);
                            bookingRepository.save(b);
                        }
                    });
                }
            });
            throw new DomainException(CommonErrorCode.CONFLICT);
        }

        if (!technicianId.toString().equals(result.getTechnicianId())
                || (claim.targetProviderId() != null && !claim.targetProviderId().toString().equals(result.getProviderId()))) {
            compensateAllocation(tx, bookingId, claim.operationId(), claim.claimToken(), "ALLOCATING", "TECHNICIAN_MISMATCH");
            throw new DomainException(CommonErrorCode.CONFLICT);
        }

        try {
            BookingResponse assigned = tx.execute(status -> {
                Instant now = Instant.now();
                var booking = bookingRepository.lockById(bookingId)
                        .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
                if (!"REQUESTED".equals(booking.getStatus())) {
                    int fenced = attemptRepository.transitionKeepingLease(
                            claim.operationId(), claim.claimToken(), "ALLOCATING", "COMPENSATION_PENDING",
                            now.plusSeconds(15), "BOOKING_NOT_REQUESTED_" + booking.getStatus(), now);
                    if (fenced == 1) {
                        booking.setAllocationStatus("COMPENSATION_PENDING");
                        booking.setUpdatedAt(now);
                        bookingRepository.save(booking);
                    }
                    return null;
                }
                int fenced = attemptRepository.transitionWithFence(
                        claim.operationId(), claim.claimToken(), "ALLOCATING", "ALLOCATED", null, now);
                if (fenced == 0) {
                    throw new DomainException(CommonErrorCode.CONFLICT);
                }
                booking.setProviderId(parseUuidOrNull(result.getProviderId()));
                booking.setAssignedTechnicianId(technicianId);
                booking.setAssignedTechnicianProfileId(result.getTechnicianProfileId());
                booking.setAssignedTechnicianName(result.getTechnicianName());
                booking.setStatus("ASSIGNED");
                booking.setAllocationStatus("ALLOCATED");
                booking.setUpdatedAt(now);
                var saved = bookingRepository.saveAndFlush(booking);
                sagaRepository.findByBookingId(bookingId).ifPresent(saga -> {
                    saga.setSagaStatus("ALLOCATED");
                    saga.setCurrentStep("COMPLETED");
                    saga.setAssignedTechnicianId(technicianId);
                    saga.setFailureReason(null);
                    saga.setCompletedAt(now);
                    saga.setUpdatedAt(now);
                    sagaRepository.save(saga);
                });
                recordHistory(bookingId, "REQUESTED", "ASSIGNED", actor.userId(), "Manually assigned technician");
                publishDomainEvent("booking.assigned.v1", saved);
                return toBookingResponse(saved);
            });
            if (assigned == null) {
                compensateAllocation(tx, bookingId, claim.operationId(), claim.claimToken(),
                        "COMPENSATION_PENDING", "BOOKING_CANCELLED_DURING_ALLOCATION");
                throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE);
            }
            return assigned;
        } catch (DomainException ex) {
            if (ex.status() == HttpStatus.CONFLICT || ex.errorCode().equals(BookingErrorCode.BOOKING_INVALID_STATE.name())) {
                throw ex;
            }
            compensateAllocation(tx, bookingId, claim.operationId(), claim.claimToken(), "ALLOCATING", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            compensateAllocation(tx, bookingId, claim.operationId(), claim.claimToken(), "ALLOCATING", ex.getMessage());
            throw ex;
        }
    }

    private void compensateAllocation(
            TransactionTemplate tx,
            UUID bookingId,
            UUID operationId,
            UUID claimToken,
            String expectedAttemptStatus,
            String reason) {
        try {
            var rel = providerGrpcClient.releaseBooking(bookingId, operationId, reason);
            if (rel != null && rel.getReleased()) {
                tx.executeWithoutResult(status -> {
                    Instant now = Instant.now();
                    int fenced = attemptRepository.transitionWithFence(
                            operationId, claimToken, expectedAttemptStatus, "COMPENSATED", reason, now);
                    if (fenced == 1) {
                        bookingRepository.findById(bookingId).ifPresent(b -> {
                            if (!"CANCELLED".equals(b.getStatus())) {
                                b.setStatus("REQUESTED");
                            }
                            b.setAllocationStatus("UNALLOCATED");
                            b.setUpdatedAt(now);
                            bookingRepository.save(b);
                        });
                    }
                });
                return;
            }
        } catch (Exception releaseEx) {
            log.warn("Immediate ReleaseBooking failed for booking {} op {}: {}", bookingId, operationId, releaseEx.getMessage());
        }
        tx.executeWithoutResult(status -> {
            Instant now = Instant.now();
            int fenced = attemptRepository.scheduleRetryWithFence(
                    operationId, claimToken, expectedAttemptStatus, "COMPENSATION_PENDING",
                    now.plusSeconds(5), reason, now);
            if (fenced == 1) {
                bookingRepository.findById(bookingId).ifPresent(b -> {
                    b.setAllocationStatus("COMPENSATION_PENDING");
                    b.setUpdatedAt(now);
                    bookingRepository.save(b);
                });
            }
        });
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
