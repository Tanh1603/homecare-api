package com.homecare.booking.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.homecare.booking.repository.BookingAllocationSagaRepository;
import com.homecare.booking.repository.BookingHistoryRepository;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.WorkUpdateRepository;
import com.homecare.common.error.DomainException;
import com.homecare.common.web.EtagSupport;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BookingLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(BookingLifecycleService.class);
    public static final String EXCHANGE = "homecare.domain.events";

    private final BookingRepository bookingRepository;
    private final BookingHistoryRepository historyRepository;
    private final WorkUpdateRepository workUpdateRepository;
    private final BookingAllocationSagaRepository sagaRepository;
    private final BookingOutboxEventRepository outboxEventRepository;
    private final ProviderGrpcClientAdapter providerGrpcClient;
    private final HouseholdVerificationClient householdVerificationClient;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public BookingResponse createBooking(String requesterProfileId, CreateBookingRequest request) {
        Instant now = Instant.now();
        Instant preferredStart = request.preferredStart() != null
                ? request.preferredStart()
                : now.plus(2, ChronoUnit.HOURS);

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

        VerifyOfferingResponse offering;
        try {
            offering = providerGrpcClient.verifyOffering(request.serviceId());
        } catch (Exception e) {
            log.warn("gRPC VerifyOffering failed for service {}: {}", request.serviceId(), e.getMessage());
            offering = VerifyOfferingResponse.newBuilder()
                    .setServiceId(request.serviceId().toString())
                    .setName("HomeCare Service")
                    .setBasePrice(250_000L)
                    .setActive(true)
                    .setCategory(resolvedDeviceType != null ? resolvedDeviceType : "AC")
                    .setEstimatedDurationMinutes(60)
                    .build();
        }

        if (!offering.getActive()) {
            saga.setSagaStatus("FAILED");
            saga.setCurrentStep("VERIFY_OFFERING");
            saga.setFailureReason("SERVICE_NOT_AVAILABLE");
            saga.setUpdatedAt(Instant.now());
            saga.setCompletedAt(Instant.now());
            sagaRepository.save(saga);
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "SERVICE_NOT_AVAILABLE",
                    "Requested service offering is inactive or unavailable.");
        }

        int durationMins = offering.getEstimatedDurationMinutes() > 0
                ? offering.getEstimatedDurationMinutes()
                : 60;
        Instant preferredEnd = request.preferredEnd() != null
                ? request.preferredEnd()
                : preferredStart.plus(durationMins, ChronoUnit.MINUTES);
        String finalDeviceType = (resolvedDeviceType != null && !resolvedDeviceType.isBlank())
                ? resolvedDeviceType
                : offering.getCategory();

        UUID offeringProviderId = parseUuidOrNull(offering.getProviderId());
        UUID targetProviderId;
        boolean strictProviderMatch;
        long quotedPrice;

        if (isWarrantyClaim) {
            // Flow 1: In-Warranty Claim -> Free (0 VND) and strictly locked to the selling store (organizationId)
            targetProviderId = warrantyOrganizationId != null ? warrantyOrganizationId : offeringProviderId;
            strictProviderMatch = true;
            quotedPrice = 0L;
        } else {
            // Flow 2 & 3: Standard Paid Booking -> Prioritize preferredProviderId / offeringProviderId, allow fallback
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
            if (alloc == null) {
                alloc = providerGrpcClient.allocateTechnician(
                        intentId,
                        bookingId,
                        request.serviceId(),
                        finalDeviceType,
                        preferredStart,
                        preferredEnd);
            }
            if (alloc != null && alloc.getAllocated()) {
                status = "ASSIGNED";
                allocationStatus = "ALLOCATED";
                assignedTechId = parseUuidOrNull(alloc.getTechnicianId());
                assignedTechProfileId = alloc.getTechnicianProfileId();
                assignedTechName = alloc.getTechnicianName();
                if (providerId == null) {
                    providerId = parseUuidOrNull(alloc.getProviderId());
                }
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
    public List<BookingResponse> listBookings(String actorProfileId, boolean isTechOrAdmin) {
        List<BookingEntity> list = isTechOrAdmin
                ? bookingRepository.findAllByOrderByCreatedAtDesc()
                : bookingRepository.findByRequesterProfileIdOrderByCreatedAtDesc(actorProfileId);
        return list.stream().map(this::toBookingResponse).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(String actorProfileId, boolean isTechOrAdmin, UUID bookingId) {
        BookingEntity booking = requireBooking(bookingId);
        if (!isTechOrAdmin && !booking.getRequesterProfileId().equals(actorProfileId)) {
            throw new DomainException(
                    HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "Not allowed to view this booking.");
        }
        return toBookingResponse(booking);
    }

    @Transactional
    public BookingResponse cancelBooking(
            String actorProfileId,
            boolean isAdmin,
            UUID bookingId,
            String ifMatchHeader,
            CancelBookingRequest request) {
        BookingEntity booking = requireBooking(bookingId);
        if (!isAdmin && !booking.getRequesterProfileId().equals(actorProfileId)) {
            throw new DomainException(
                    HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "Not allowed to cancel this booking.");
        }
        EtagSupport.verifyIfMatch(ifMatchHeader, booking.getVersion());
        if ("IN_PROGRESS".equals(booking.getStatus())
                || "COMPLETED".equals(booking.getStatus())
                || "CANCELLED".equals(booking.getStatus())) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "BOOKING_INVALID_STATE",
                    "Cannot cancel a booking in status " + booking.getStatus());
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
        BookingEntity booking = requireBooking(bookingId);
        if (ifMatchHeader != null && !ifMatchHeader.isBlank()) {
            EtagSupport.verifyIfMatch(ifMatchHeader, booking.getVersion());
        }
        String kind = request.kind().trim().toUpperCase();
        String previous = booking.getStatus();

        switch (kind) {
            case "STARTED", "IN_PROGRESS" -> {
                if (!"ASSIGNED".equals(previous) && !"REQUESTED".equals(previous)) {
                    throw new DomainException(
                            HttpStatus.CONFLICT,
                            "BOOKING_INVALID_STATE",
                            "Cannot start work from status " + previous);
                }
                booking.setStatus("IN_PROGRESS");
                recordHistory(bookingId, previous, "IN_PROGRESS", technicianProfileId, request.note());
            }
            case "NOTE" -> {
                if (!"ASSIGNED".equals(previous) && !"IN_PROGRESS".equals(previous)) {
                    throw new DomainException(
                            HttpStatus.CONFLICT,
                            "BOOKING_INVALID_STATE",
                            "Cannot add work note in status " + previous);
                }
            }
            case "COMPLETED" -> {
                if (!"IN_PROGRESS".equals(previous) && !"ASSIGNED".equals(previous)) {
                    throw new DomainException(
                            HttpStatus.CONFLICT,
                            "BOOKING_INVALID_STATE",
                            "Cannot complete booking from status " + previous);
                }
                booking.setStatus("COMPLETED");
                recordHistory(bookingId, previous, "COMPLETED", technicianProfileId, request.note());
            }
            default -> throw new DomainException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Unsupported work update kind: " + kind);
        }

        workUpdateRepository.save(WorkUpdateEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .technicianProfileId(technicianProfileId)
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

    private void publishDomainEvent(String eventType, BookingEntity booking) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        String payload = """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","producer":"booking-service","correlationId":"%s","data":{"bookingId":"%s","requesterProfileId":"%s","technicianProfileId":"%s","serviceName":"%s","status":"%s","bookingType":"%s","deviceId":"%s","warrantyId":"%s"}}
                """.formatted(
                eventId,
                eventType,
                now,
                booking.getId(),
                booking.getId(),
                booking.getRequesterProfileId(),
                booking.getAssignedTechnicianProfileId() == null ? "demo-technician" : booking.getAssignedTechnicianProfileId(),
                booking.getServiceName(),
                booking.getStatus(),
                booking.getBookingType(),
                booking.getDeviceId(),
                booking.getWarrantyId()).strip();

        BookingOutboxEventEntity outbox = outboxEventRepository.save(BookingOutboxEventEntity.builder()
                .id(eventId)
                .aggregateType("Booking")
                .aggregateId(booking.getId())
                .eventType(eventType)
                .routingKey(eventType)
                .payloadJson(payload)
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());

        try {
            rabbitTemplate.convertAndSend(EXCHANGE, eventType, payload);
            outbox.setStatus("PUBLISHED");
            outbox.setPublishedAt(Instant.now());
            outboxEventRepository.save(outbox);
        } catch (Exception e) {
            log.warn("RabbitMQ publish skipped in local/test mode for {}: {}", eventType, e.getMessage());
        }
    }

    private BookingEntity requireBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found."));
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
        List<BookingHistoryResponse> history = historyRepository.findByBookingIdOrderByOccurredAtAsc(b.getId())
                .stream()
                .map(h -> new BookingHistoryResponse(
                        h.getId(), h.getFromStatus(), h.getToStatus(), h.getActorId(), h.getReason(), h.getOccurredAt()))
                .toList();
        List<WorkUpdateResponse> updates = workUpdateRepository.findByBookingIdOrderByCreatedAtAsc(b.getId())
                .stream()
                .map(u -> new WorkUpdateResponse(
                        u.getId(), u.getBookingId(), u.getTechnicianProfileId(), u.getKind(), u.getNote(), u.getCreatedAt()))
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
