package com.homecare.booking.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.WorkEvidenceResponse;
import com.homecare.booking.dto.BookingExecutionDtos.CustomerNoShowRequest;
import com.homecare.booking.dto.BookingExecutionDtos.ResumeFromPartsRequest;
import com.homecare.booking.dto.BookingExecutionDtos.SubmitCompletionRequest;
import com.homecare.booking.dto.BookingExecutionDtos.WaitingForPartsRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingPurpose;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.entity.WorkEvidenceEntity;
import com.homecare.booking.entity.WorkUpdateEntity;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.booking.repository.BookingPartsRequestRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.QuotationRepository;
import com.homecare.booking.repository.WorkEvidenceRepository;
import com.homecare.booking.repository.WorkUpdateRepository;
import com.homecare.booking.security.BookingAccessPolicy;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.ActorContext;
import com.homecare.common.web.EtagSupport;

import lombok.RequiredArgsConstructor;

/**
 * Cohesive service for Field Execution (Feature 4):
 * - Lock-free work evidence upload (Guardrail #14)
 * - WAITING_FOR_PARTS pause / same-org resume (D-26, D-30)
 * - CUSTOMER_NO_SHOW 30-minute guard + evidence (D-26, D-30)
 * - Evidence-gated completion -> COMPLETED_PENDING_ACCEPTANCE (D-11.2)
 */
@Service
@RequiredArgsConstructor
public class BookingExecutionService {

    private static final Logger log = LoggerFactory.getLogger(BookingExecutionService.class);
    private static final long MAX_EVIDENCE_BYTES = 5L * 1024L * 1024L;
    private static final long CUSTOMER_NO_SHOW_MIN_WAIT_MINUTES = 30L;
    private static final Set<String> ALLOWED_EVIDENCE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp");
    private static final Set<String> EVIDENCE_UPLOAD_STATUSES = Set.of(
            BookingStatus.ASSIGNED.name(),
            BookingStatus.IN_PROGRESS.name(),
            BookingStatus.WAITING_FOR_PARTS.name(),
            BookingStatus.REVISION_REQUESTED.name(),
            BookingStatus.DISPUTED.name());

    private final BookingRepository bookingRepository;
    private final WorkEvidenceRepository workEvidenceRepository;
    private final WorkUpdateRepository workUpdateRepository;
    private final QuotationRepository quotationRepository;
    private final WorkEvidenceStoragePort workEvidenceStorage;
    private final BookingAccessPolicy accessPolicy;
    private final BookingLifecycleService lifecycleService;
    private final ObjectProvider<ProviderGrpcClientAdapter> providerGrpcClientProvider;
    private final ObjectProvider<BookingPartsRequestRepository> partsRequestRepositoryProvider;
    private final PlatformTransactionManager transactionManager;

    // Never @Transactional: remote HTTP upload to Supabase Storage must NOT hold DB locks (Guardrail #14).
    public WorkEvidenceResponse uploadWorkEvidence(
            ActorContext actor,
            UUID bookingId,
            String originalFilename,
            String contentType,
            byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_EVIDENCE_BYTES) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Evidence file must be 1 byte..5 MB.");
        }
        String normalizedType = contentType == null ? "" : contentType.trim().toLowerCase();
        if (!ALLOWED_EVIDENCE_TYPES.contains(normalizedType)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Unsupported evidence image contentType.");
        }

        // 1. Short read-only validation outside write lock
        BookingEntity snapshot = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        assertCanUploadEvidence(actor, snapshot);
        if (!EVIDENCE_UPLOAD_STATUSES.contains(snapshot.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE);
        }

        // 2. Remote HTTP upload to Supabase Storage with ZERO DB lock held
        WorkEvidenceStoragePort.StoredObject stored = workEvidenceStorage.upload(
                bookingId, originalFilename, normalizedType, bytes);

        // 3. Persist metadata in short REQUIRES_NEW transaction
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        WorkEvidenceEntity saved = tx.execute(status -> {
            BookingEntity current = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
            if (!EVIDENCE_UPLOAD_STATUSES.contains(current.getStatus())) {
                log.warn("Booking {} moved to {} during upload; orphaned object {}/{}",
                        bookingId, current.getStatus(), stored.bucket(), stored.objectKey());
                throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE);
            }
            return workEvidenceRepository.saveAndFlush(WorkEvidenceEntity.builder()
                    .id(UUID.randomUUID())
                    .bookingId(bookingId)
                    .uploaderProfileId(actor.userId())
                    .bucket(stored.bucket())
                    .objectKey(stored.objectKey())
                    .contentType(normalizedType)
                    .sizeBytes(bytes.length)
                    .status("UPLOADED")
                    .createdAt(Instant.now())
                    .build());
        });
        return new WorkEvidenceResponse(
                saved.getId(), saved.getBookingId(), saved.getContentType(), saved.getSizeBytes(), saved.getCreatedAt(), null);
    }

    @Transactional
    public BookingResponse markWaitingForParts(
            ActorContext actor,
            UUID bookingId,
            String ifMatch,
            WaitingForPartsRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        accessPolicy.assertCanAppendWorkUpdate(actor, booking);
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!BookingStatus.IN_PROGRESS.name().equals(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Only IN_PROGRESS bookings can transition to WAITING_FOR_PARTS.");
        }
        Instant now = Instant.now();
        String previous = booking.getStatus();
        // D-30: Release technician allocation slot when entering WAITING_FOR_PARTS
        lifecycleService.releaseActiveAllocationIfPresent(booking, "WAITING_FOR_PARTS: " + request.reason().trim());
        booking.setStatus(BookingStatus.WAITING_FOR_PARTS.name());
        booking.setWaitingForPartsSince(now);
        booking.setWaitingForPartsReminderStage(0);
        booking.setUpdatedAt(now);
        BookingEntity saved = bookingRepository.saveAndFlush(booking);
        lifecycleService.recordHistory(bookingId, previous, saved.getStatus(), actor.userId(),
                "WAITING_FOR_PARTS: " + request.reason().trim());
        lifecycleService.publishDomainEvent(DomainEventContracts.BOOKING_WAITING_FOR_PARTS, saved);
        return lifecycleService.toBookingResponse(saved);
    }

    @Transactional
    public BookingResponse resumeFromWaitingForParts(
            ActorContext actor,
            UUID bookingId,
            String ifMatch,
            ResumeFromPartsRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        accessPolicy.assertCanAppendWorkUpdate(actor, booking);
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!BookingStatus.WAITING_FOR_PARTS.name().equals(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Only WAITING_FOR_PARTS bookings can be resumed.");
        }
        Instant now = Instant.now();
        String previous = booking.getStatus();
        String oldTechnicianProfileId = booking.getAssignedTechnicianProfileId();
        boolean reassignedDifferentTech = false;
        if (request != null && request.technicianId() != null) {
            accessPolicy.assertCanAssign(actor, booking);
            reassignedDifferentTech = !request.technicianId().equals(booking.getAssignedTechnicianId());
            applySameOrgTechnicianReassignment(
                    booking,
                    request.technicianId(),
                    request.technicianProfileId(),
                    request.technicianName(),
                    "RESUMED_FROM_WAITING_FOR_PARTS");
        } else if (booking.getAssignedTechnicianId() != null
                && (!"ALLOCATED".equalsIgnoreCase(booking.getAllocationStatus())
                        || booking.getActiveAllocationOperationId() == null)) {
            applySameOrgTechnicianReassignment(
                    booking,
                    booking.getAssignedTechnicianId(),
                    booking.getAssignedTechnicianProfileId(),
                    booking.getAssignedTechnicianName(),
                    "RESUMED_FROM_WAITING_FOR_PARTS");
        }
        booking.setStatus(BookingStatus.IN_PROGRESS.name());
        booking.setWaitingForPartsSince(null);
        booking.setWaitingForPartsReminderStage(0);
        booking.setUpdatedAt(now);
        BookingEntity saved = bookingRepository.saveAndFlush(booking);
        String note = request != null && request.note() != null && !request.note().isBlank()
                ? request.note().trim()
                : "Resumed from WAITING_FOR_PARTS";
        lifecycleService.recordHistory(bookingId, previous, saved.getStatus(), actor.userId(), note);
        if (reassignedDifferentTech) {
            lifecycleService.publishBookingReassignedEvent(saved, oldTechnicianProfileId, note, now);
        }
        return lifecycleService.toBookingResponse(saved);
    }

    @Transactional
    public BookingResponse reassignSameOrgTechnician(
            ActorContext actor,
            UUID bookingId,
            String ifMatch,
            ResumeFromPartsRequest request) {
        if (request == null || request.technicianId() == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "technicianId is required.");
        }
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        accessPolicy.assertCanAssign(actor, booking);
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!Set.of(
                BookingStatus.WAITING_FOR_PARTS.name(),
                BookingStatus.REVISION_REQUESTED.name(),
                BookingStatus.IN_PROGRESS.name()).contains(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Same-org technician reassignment is only allowed in WAITING_FOR_PARTS, REVISION_REQUESTED, or IN_PROGRESS.");
        }
        Instant now = Instant.now();
        String previous = booking.getStatus();
        String oldTechnicianProfileId = booking.getAssignedTechnicianProfileId();
        String reason = request.note() != null && !request.note().isBlank()
                ? request.note().trim()
                : "Same-org technician reassigned";
        applySameOrgTechnicianReassignment(
                booking,
                request.technicianId(),
                request.technicianProfileId(),
                request.technicianName(),
                reason);
        booking.setUpdatedAt(now);
        BookingEntity saved = bookingRepository.saveAndFlush(booking);
        lifecycleService.recordHistory(bookingId, previous, saved.getStatus(), actor.userId(), reason);
        lifecycleService.publishBookingReassignedEvent(saved, oldTechnicianProfileId, reason, now);
        return lifecycleService.toBookingResponse(saved);
    }

    private void applySameOrgTechnicianReassignment(
            BookingEntity booking,
            UUID targetTechnicianId,
            String targetProfileId,
            String targetName,
            String reason) {
        ProviderGrpcClientAdapter grpcClient = providerGrpcClientProvider.getIfAvailable();
        if (grpcClient != null && booking.getProviderId() != null) {
            UUID opId = UUID.randomUUID();
            boolean needsFreshAllocation = !"ALLOCATED".equalsIgnoreCase(booking.getAllocationStatus())
                    || booking.getActiveAllocationOperationId() == null;
            AllocateTechnicianResponse resp;
            if (needsFreshAllocation) {
                Instant start = booking.getPreferredStart() != null ? booking.getPreferredStart() : Instant.now();
                Instant end = booking.getPreferredEnd() != null ? booking.getPreferredEnd() : start.plus(Duration.ofHours(2));
                resp = grpcClient.assignTechnician(
                        opId,
                        booking.getId(),
                        booking.getServiceId(),
                        booking.getDeviceType(),
                        start,
                        end,
                        booking.getProviderId(),
                        targetTechnicianId);
            } else {
                resp = grpcClient.reassignTechnician(
                        opId,
                        booking.getId(),
                        targetTechnicianId,
                        booking.getProviderId(),
                        reason);
            }
            if (resp != null && !resp.getAllocated()) {
                throw new DomainException(CommonErrorCode.CONFLICT,
                        resp.getFailureReason().isBlank()
                                ? "Technician does not belong to this organization or is unavailable."
                                : resp.getFailureReason());
            }
            if (resp != null && !resp.getTechnicianId().isBlank()) {
                booking.setAssignedTechnicianId(UUID.fromString(resp.getTechnicianId()));
                if (!resp.getTechnicianProfileId().isBlank()) {
                    booking.setAssignedTechnicianProfileId(resp.getTechnicianProfileId());
                }
                if (!resp.getTechnicianName().isBlank()) {
                    booking.setAssignedTechnicianName(resp.getTechnicianName());
                }
                booking.setActiveAllocationOperationId(opId);
                booking.setAllocationStatus("ALLOCATED");
                return;
            }
        }
        booking.setAssignedTechnicianId(targetTechnicianId);
        if (targetProfileId != null && !targetProfileId.isBlank()) {
            booking.setAssignedTechnicianProfileId(targetProfileId.trim());
        }
        if (targetName != null && !targetName.isBlank()) {
            booking.setAssignedTechnicianName(targetName.trim());
        }
        booking.setAllocationStatus("ALLOCATED");
    }

    @Transactional
    public BookingResponse reportCustomerNoShow(
            ActorContext actor,
            UUID bookingId,
            String ifMatch,
            CustomerNoShowRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        accessPolicy.assertCanAppendWorkUpdate(actor, booking);
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!BookingStatus.ASSIGNED.name().equals(booking.getStatus())
                && !BookingStatus.IN_PROGRESS.name().equals(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Customer No-Show can only be reported in ASSIGNED or IN_PROGRESS after arrival.");
        }
        Instant now = Instant.now();
        if (booking.getArrivedAt() == null
                || Duration.between(booking.getArrivedAt(), now).toMinutes() < CUSTOMER_NO_SHOW_MIN_WAIT_MINUTES) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Technician must have arrived and waited at least 30 minutes before reporting CUSTOMER_NO_SHOW (D-26).");
        }
        WorkEvidenceEntity evidence = workEvidenceRepository.findById(request.evidenceId())
                .filter(ev -> bookingId.equals(ev.getBookingId()))
                .orElseThrow(() -> new DomainException(CommonErrorCode.VALIDATION_FAILED,
                        "Valid site arrival photo evidence is required for CUSTOMER_NO_SHOW (D-26)."));

        String previous = booking.getStatus();
        lifecycleService.releaseActiveAllocationIfPresent(booking, "CUSTOMER_NO_SHOW");
        booking.setStatus(BookingStatus.CUSTOMER_NO_SHOW.name());
        booking.setQuotedPrice(0L);
        booking.setCustomerNoShowEvidenceId(evidence.getId());
        booking.setUpdatedAt(now);
        BookingEntity saved = bookingRepository.saveAndFlush(booking);
        lifecycleService.recordHistory(bookingId, previous, saved.getStatus(), actor.userId(),
                "[CUSTOMER_NO_SHOW_STRIKE] " + request.callLogNote().trim());
        lifecycleService.publishDomainEvent(DomainEventContracts.BOOKING_CUSTOMER_NO_SHOW, saved);
        return lifecycleService.toBookingResponse(saved);
    }

    @Transactional
    public BookingResponse submitCompletionForAcceptance(
            ActorContext actor,
            UUID bookingId,
            String ifMatch,
            SubmitCompletionRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        accessPolicy.assertCanAppendWorkUpdate(actor, booking);
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!BookingStatus.IN_PROGRESS.name().equals(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Only IN_PROGRESS bookings can be submitted for acceptance.");
        }
        if (request == null || request.workSummary() == null || request.workSummary().isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "A non-blank workSummary is required to complete a booking (D-11.2).");
        }

        if (BookingPurpose.INSTALLATION.name().equals(booking.getPurpose()) && !booking.isProductReady()) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Installation cannot complete before product delivery.");
        }
        if (workUpdateRepository.existsByBookingIdAndExtraCostStatus(bookingId, "PENDING_APPROVAL")) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Cannot submit completion while an extra-cost work update is awaiting customer approval.");
        }

        // Block completion if any REVISED quotation is still SENT (FR-009 / D-11.2)
        boolean hasPendingRevised = quotationRepository
                .findByBookingIdAndQuoteKindOrderByVersionDesc(bookingId, QuotationService.QUOTE_KIND_REVISED)
                .stream()
                .anyMatch(q -> "SENT".equals(q.getStatus()));
        if (hasPendingRevised) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Cannot submit completion while a REVISED quotation is awaiting customer approval.");
        }

        // BR-EVID-01: Block completion while approved/pending parts requests remain unrecorded
        BookingPartsRequestRepository partsReqRepo = partsRequestRepositoryProvider != null
                ? partsRequestRepositoryProvider.getIfAvailable()
                : null;
        if (partsReqRepo != null && partsReqRepo.existsByBookingIdAndStatusIn(
                bookingId, List.of("PENDING_PROVIDER_APPROVAL", "PROVIDER_APPROVED"))) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Cannot submit completion while approved or pending parts requests lack a Parts Usage Record (BR-EVID-01).");
        }

        // D-11.2: Require >= 1 valid photo evidence belonging to this booking
        List<WorkEvidenceEntity> bookingEvidence = workEvidenceRepository.findByBookingId(bookingId);
        List<UUID> requestedIds = request.evidenceIds() != null ? request.evidenceIds() : List.of();
        List<WorkEvidenceEntity> validEvidence = requestedIds.isEmpty()
                ? bookingEvidence
                : bookingEvidence.stream().filter(ev -> requestedIds.contains(ev.getId())).toList();
        if (validEvidence.isEmpty()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "At least 1 valid completion photo evidence is required before entering COMPLETED_PENDING_ACCEPTANCE (D-11.2).");
        }

        Instant now = Instant.now();
        String previous = booking.getStatus();
        WorkUpdateEntity completionUpdate = workUpdateRepository.saveAndFlush(WorkUpdateEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .technicianProfileId(actor.userId())
                .kind("COMPLETED")
                .note(request.workSummary().trim())
                .extraCostStatus("NONE")
                .createdAt(now)
                .build());
        for (WorkEvidenceEntity ev : validEvidence) {
            if (ev.getWorkUpdateId() == null) {
                ev.setWorkUpdateId(completionUpdate.getId());
                workEvidenceRepository.save(ev);
            }
        }

        lifecycleService.releaseActiveAllocationIfPresent(booking, "COMPLETED", false);
        booking.setStatus(BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name());
        booking.setCompletionSubmittedAt(now);
        booking.setCompletionSummary(request.workSummary().trim());
        booking.setCompletionNotifiedAt(null);
        booking.setNotificationFailed(false);
        booking.setAutoAcceptAt(null);
        booking.setAcceptanceReminderSentAt(null);
        booking.setUpdatedAt(now);
        BookingEntity saved = bookingRepository.saveAndFlush(booking);

        lifecycleService.recordHistory(bookingId, previous, saved.getStatus(), actor.userId(),
                "Submitted completion for customer acceptance");
        lifecycleService.publishDomainEvent(DomainEventContracts.BOOKING_COMPLETION_PENDING_ACCEPTANCE, saved);
        return lifecycleService.toBookingResponse(saved);
    }

    private void assertCanUploadEvidence(ActorContext actor, BookingEntity booking) {
        if (BookingStatus.DISPUTED.name().equals(booking.getStatus())
                && booking.getRequesterProfileId().equals(actor.userId())) {
            return;
        }
        accessPolicy.assertCanAppendWorkUpdate(actor, booking);
    }
}
