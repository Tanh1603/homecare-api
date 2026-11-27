package com.homecare.booking.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.homecare.booking.dto.DisputeDtos.DisputeEvidenceResponse;
import com.homecare.booking.dto.DisputeDtos.DisputeResponse;
import com.homecare.booking.dto.DisputeDtos.OpenDisputeRequest;
import com.homecare.booking.dto.DisputeDtos.ProposeResolutionRequest;
import com.homecare.booking.dto.DisputeDtos.ProviderDisputeResponseRequest;
import com.homecare.booking.dto.DisputeDtos.RequestSlaActionRequest;
import com.homecare.booking.dto.DisputeDtos.ResolveDisputeRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.entity.DisputeEntity;
import com.homecare.booking.entity.DisputeEvidenceEntity;
import com.homecare.booking.entity.DisputeStatus;
import com.homecare.booking.entity.WorkEvidenceEntity;
import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.DisputeEvidenceRepository;
import com.homecare.booking.repository.DisputeRepository;
import com.homecare.booking.repository.WorkEvidenceRepository;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Independent Dispute aggregate service (D-07, D-11.4, D-27, D-30).
 */
@Service
@RequiredArgsConstructor
public class DisputeService {

    public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final long POST_ACCEPT_DISPUTE_WINDOW_DAYS = 30L;
    private static final long MAX_EVIDENCE_BYTES = 5L * 1024L * 1024L;
    private static final Set<String> ALLOWED_EVIDENCE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "application/pdf");
    private static final Set<String> TERMINAL_DISPUTE_STATUSES = Set.of(
            DisputeStatus.RESOLVED.name(), DisputeStatus.ESCALATED.name(), DisputeStatus.CLOSED.name());

    private final DisputeRepository disputeRepository;
    private final DisputeEvidenceRepository evidenceRepository;
    private final BookingRepository bookingRepository;
    private final WorkEvidenceRepository workEvidenceRepository;
    private final BookingOutboxEventRepository outboxRepository;
    private final WorkEvidenceStoragePort storagePort;
    private final BookingLifecycleService lifecycleService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public DisputeResponse openDispute(ActorContext actor, UUID bookingId, OpenDisputeRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        if (!actor.isGlobalAdmin() && !booking.getRequesterProfileId().equals(actor.userId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Only the customer can open a dispute.");
        }
        if (disputeRepository.findActiveByBookingId(bookingId).isPresent()) {
            throw new DomainException(CommonErrorCode.CONFLICT, "An active dispute already exists for this booking.");
        }
        if (booking.getProviderId() == null) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Booking has no assigned provider.");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String status = booking.getStatus();
        String openedStage;
        if (Set.of(
                BookingStatus.IN_PROGRESS.name(),
                BookingStatus.WAITING_FOR_PARTS.name(),
                BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name(),
                BookingStatus.REVISION_REQUESTED.name()).contains(status)) {
            openedStage = "PRE_ACCEPTANCE";
            booking.setStatus(BookingStatus.DISPUTED.name());
            booking.setAutoAcceptAt(null);
            booking.setRevisionRequestedAt(null);
            booking.setUpdatedAt(now);
            bookingRepository.saveAndFlush(booking);
            lifecycleService.recordHistory(bookingId, status, BookingStatus.DISPUTED.name(), actor.userId(),
                    "Customer opened dispute: " + request.category().trim());
        } else if (BookingStatus.ACCEPTED.name().equals(status)) {
            openedStage = "POST_ACCEPTANCE";
            Instant acceptedAt = booking.getAcceptedAt() != null ? booking.getAcceptedAt() : booking.getUpdatedAt();
            LocalDate day0 = acceptedAt.atZone(VN_ZONE).toLocalDate();
            Instant deadline = day0.plusDays(POST_ACCEPT_DISPUTE_WINDOW_DAYS)
                    .atTime(23, 59, 59, 999_000_000)
                    .atZone(VN_ZONE)
                    .toInstant();
            if (now.isAfter(deadline)) {
                throw new DomainException(CommonErrorCode.CONFLICT,
                        "Post-acceptance dispute window (30 calendar days) has expired (D-30).");
            }
            // D-11.4: Booking remains ACCEPTED; never reverted
        } else {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Dispute cannot be opened for booking in status " + status + ".");
        }

        DisputeEntity saved = disputeRepository.saveAndFlush(DisputeEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .openedByProfileId(actor.userId())
                .providerId(booking.getProviderId())
                .openedStage(openedStage)
                .category(request.category().trim().toUpperCase())
                .description(request.description().trim())
                .status(DisputeStatus.SUBMITTED.name())
                .createdAt(now)
                .updatedAt(now)
                .build());

        linkWorkEvidenceIfProvided(saved.getId(), bookingId, actor.userId(), "CUSTOMER", request.evidenceIds(), now);
        publishDisputeEvent(DomainEventContracts.DISPUTE_OPENED, saved, booking.getRequesterProfileId(), now);
        return toResponse(saved);
    }

    @Transactional
    public DisputeEntity openSystemDisputeForBooking(
            BookingEntity booking,
            String openedByProfileId,
            String category,
            String description,
            Instant now) {
        return disputeRepository.findActiveByBookingId(booking.getId()).orElseGet(() -> {
            DisputeEntity saved = disputeRepository.saveAndFlush(DisputeEntity.builder()
                    .id(UUID.randomUUID())
                    .bookingId(booking.getId())
                    .openedByProfileId(openedByProfileId)
                    .providerId(booking.getProviderId())
                    .openedStage("PRE_ACCEPTANCE")
                    .category(category)
                    .description(description)
                    .status(DisputeStatus.SUBMITTED.name())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            publishDisputeEvent(DomainEventContracts.DISPUTE_OPENED, saved, booking.getRequesterProfileId(), now);
            return saved;
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<DisputeResponse> adminListDisputes(ActorContext actor, String status, int page, int size) {
        assertAdmin(actor);
        PageResponse.validate(page, size);
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by("createdAt").descending());
        Page<DisputeEntity> result = (status != null && !status.isBlank())
                ? disputeRepository.findByStatus(status.trim().toUpperCase(), pageable)
                : disputeRepository.findAll(pageable);
        return PageResponse.of(result.getContent().stream().map(this::toResponse).toList(),
                page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> listBookingDisputes(ActorContext actor, UUID bookingId) {
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        boolean canView = actor.isGlobalAdmin()
                || booking.getRequesterProfileId().equals(actor.userId())
                || (actor.mode() == AccessMode.ORGANIZATION
                        && actor.organizationId() != null
                        && actor.organizationId().equals(booking.getProviderId()));
        if (!canView) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        return disputeRepository.findByBookingIdOrderByCreatedAtDesc(bookingId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<DisputeResponse> listDisputes(ActorContext actor, String status, int page, int size) {
        PageResponse.validate(page, size);
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by("createdAt").descending());
        Page<DisputeEntity> result;
        if (actor.isGlobalAdmin() && actor.mode() == AccessMode.PERSONAL) {
            result = (status != null && !status.isBlank())
                    ? disputeRepository.findByStatus(status.trim().toUpperCase(), pageable)
                    : disputeRepository.findAll(pageable);
        } else if (actor.mode() == AccessMode.ORGANIZATION && actor.organizationId() != null) {
            result = disputeRepository.findByProviderId(actor.organizationId(), pageable);
        } else {
            result = disputeRepository.findByOpenedByProfileId(actor.userId(), pageable);
        }
        return PageResponse.of(result.getContent().stream().map(this::toResponse).toList(),
                page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public DisputeResponse getDispute(ActorContext actor, UUID disputeId) {
        DisputeEntity dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Dispute not found."));
        assertCanView(actor, dispute);
        return toResponse(dispute);
    }

    // Lock-free remote upload outside DB transaction (Guardrail #14)
    public DisputeEvidenceResponse uploadDisputeEvidence(
            ActorContext actor,
            UUID disputeId,
            String originalFilename,
            String contentType,
            byte[] bytes,
            String note) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_EVIDENCE_BYTES) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Evidence file must be 1 byte..5 MB.");
        }
        String normalizedType = contentType == null ? "" : contentType.trim().toLowerCase();
        if (!ALLOWED_EVIDENCE_TYPES.contains(normalizedType)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Unsupported dispute evidence contentType.");
        }
        DisputeEntity snapshot = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Dispute not found."));
        String role = resolveUploaderRole(actor, snapshot);
        if (TERMINAL_DISPUTE_STATUSES.contains(snapshot.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Dispute is closed.");
        }

        WorkEvidenceStoragePort.StoredObject stored = storagePort.upload(
                snapshot.getBookingId(), originalFilename, normalizedType, bytes);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        DisputeEvidenceEntity saved = tx.execute(s -> evidenceRepository.saveAndFlush(DisputeEvidenceEntity.builder()
                .id(UUID.randomUUID())
                .disputeId(disputeId)
                .uploadedByProfileId(actor.userId())
                .uploaderRole(role)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(normalizedType)
                .sizeBytes(bytes.length)
                .note(note != null && !note.isBlank() ? note.trim() : null)
                .createdAt(Instant.now())
                .build()));
        return toEvidenceResponse(saved);
    }

    @Transactional
    public DisputeResponse submitProviderResponse(
            ActorContext actor,
            UUID disputeId,
            String ifMatch,
            ProviderDisputeResponseRequest request) {
        DisputeEntity dispute = disputeRepository.lockById(disputeId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Dispute not found."));
        if (actor.mode() != AccessMode.ORGANIZATION
                || actor.organizationId() == null
                || !actor.organizationId().equals(dispute.getProviderId())
                || !actor.hasOrgPermission(com.homecare.common.security.RbacConstants.PERM_BOOKING_DISPATCH)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Only authorized organization dispatchers of the assigned provider can respond.");
        }
        EtagSupport.verifyIfMatch(ifMatch, dispute.getVersion());
        if (!DisputeStatus.AWAITING_PROVIDER_RESPONSE.name().equals(dispute.getStatus())
                && !DisputeStatus.SUBMITTED.name().equals(dispute.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Provider cannot submit response in dispute status " + dispute.getStatus());
        }
        Instant now = Instant.now();
        dispute.setProviderResponse(request.response().trim());
        dispute.setProviderRespondedAt(now);
        dispute.setProviderNoResponse(false);
        dispute.setStatus(DisputeStatus.UNDER_REVIEW.name());
        dispute.setSlaDueAt(null);
        dispute.setSlaReminderStage(0);
        dispute.setUpdatedAt(now);
        DisputeEntity saved = disputeRepository.saveAndFlush(dispute);
        linkWorkEvidenceIfProvided(disputeId, dispute.getBookingId(), actor.userId(), "PROVIDER", request.evidenceIds(), now);
        return toResponse(saved);
    }

    @Transactional
    public DisputeResponse adminRequestProviderResponse(
            ActorContext actor, UUID disputeId, String ifMatch, RequestSlaActionRequest request) {
        assertAdmin(actor);
        DisputeEntity dispute = disputeRepository.lockById(disputeId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Dispute not found."));
        EtagSupport.verifyIfMatch(ifMatch, dispute.getVersion());
        if (TERMINAL_DISPUTE_STATUSES.contains(dispute.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Dispute is already closed.");
        }
        Instant now = Instant.now();
        dispute.setStatus(DisputeStatus.AWAITING_PROVIDER_RESPONSE.name());
        // D-27: 48h calendar SLA for Provider response
        dispute.setSlaDueAt(now.plus(48, ChronoUnit.HOURS));
        dispute.setSlaReminderStage(0);
        dispute.setUpdatedAt(now);
        return toResponse(disputeRepository.saveAndFlush(dispute));
    }

    @Transactional
    public DisputeResponse adminRequestCustomerEvidence(
            ActorContext actor, UUID disputeId, String ifMatch, RequestSlaActionRequest request) {
        assertAdmin(actor);
        DisputeEntity dispute = disputeRepository.lockById(disputeId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Dispute not found."));
        EtagSupport.verifyIfMatch(ifMatch, dispute.getVersion());
        if (TERMINAL_DISPUTE_STATUSES.contains(dispute.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Dispute is already closed.");
        }
        Instant now = Instant.now();
        dispute.setStatus(DisputeStatus.AWAITING_CUSTOMER_EVIDENCE.name());
        // D-30: 7 calendar days (Asia/Ho_Chi_Minh) Customer SLA
        dispute.setSlaDueAt(BookingAcceptanceService.computeAutoAcceptAt(now));
        dispute.setSlaReminderStage(0);
        dispute.setUpdatedAt(now);
        return toResponse(disputeRepository.saveAndFlush(dispute));
    }

    @Transactional
    public DisputeResponse adminProposeResolution(
            ActorContext actor, UUID disputeId, String ifMatch, ProposeResolutionRequest request) {
        assertAdmin(actor);
        DisputeEntity dispute = disputeRepository.lockById(disputeId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Dispute not found."));
        EtagSupport.verifyIfMatch(ifMatch, dispute.getVersion());
        if (TERMINAL_DISPUTE_STATUSES.contains(dispute.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Dispute is already closed.");
        }
        Instant now = Instant.now();
        dispute.setResolutionProposal(request.proposal().trim());
        dispute.setResolutionOutcome(request.outcome().trim().toUpperCase());
        dispute.setStatus(DisputeStatus.RESOLUTION_PROPOSED.name());
        dispute.setSlaDueAt(BookingAcceptanceService.computeAutoAcceptAt(now));
        dispute.setSlaReminderStage(0);
        dispute.setUpdatedAt(now);
        return toResponse(disputeRepository.saveAndFlush(dispute));
    }

    @Transactional
    public DisputeResponse adminResolveDispute(
            ActorContext actor, UUID disputeId, String ifMatch, ResolveDisputeRequest request) {
        assertAdmin(actor);
        DisputeEntity dispute = disputeRepository.lockById(disputeId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Dispute not found."));
        EtagSupport.verifyIfMatch(ifMatch, dispute.getVersion());
        if (TERMINAL_DISPUTE_STATUSES.contains(dispute.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Dispute is already closed.");
        }
        String targetStatus = request.outcome().trim().toUpperCase();
        if (!TERMINAL_DISPUTE_STATUSES.contains(targetStatus)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "outcome must be RESOLVED, ESCALATED, or CLOSED.");
        }

        Instant now = Instant.now();
        dispute.setStatus(targetStatus);
        dispute.setResolutionOutcome(request.bookingAction() != null && !request.bookingAction().isBlank()
                ? request.bookingAction().trim().toUpperCase()
                : targetStatus);
        dispute.setResolvedByAdminId(actor.userId());
        dispute.setResolutionNote(request.note().trim());
        dispute.setSlaDueAt(null);
        dispute.setClosedAt(now);
        dispute.setUpdatedAt(now);
        DisputeEntity saved = disputeRepository.saveAndFlush(dispute);

        // Apply Booking transition ONLY for PRE_ACCEPTANCE disputes (D-11.4, D-27)
        BookingEntity booking = bookingRepository.lockById(dispute.getBookingId()).orElse(null);
        if (booking != null && "PRE_ACCEPTANCE".equals(dispute.getOpenedStage())
                && BookingStatus.DISPUTED.name().equals(booking.getStatus())) {
            String action = request.bookingAction() != null ? request.bookingAction().trim().toUpperCase() : "";
            if (DisputeStatus.ESCALATED.name().equals(targetStatus) || "CLOSE_UNRESOLVED".equals(action)) {
                booking.setStatus(BookingStatus.CLOSED_UNRESOLVED.name());
                booking.setUpdatedAt(now);
                bookingRepository.saveAndFlush(booking);
                lifecycleService.recordHistory(booking.getId(), BookingStatus.DISPUTED.name(),
                        BookingStatus.CLOSED_UNRESOLVED.name(), actor.userId(),
                        "Dispute escalated/closed unresolved (D-27): " + request.note().trim());
            } else if ("REWORK".equals(action)) {
                booking.setStatus(BookingStatus.IN_PROGRESS.name());
                booking.setUpdatedAt(now);
                bookingRepository.saveAndFlush(booking);
                lifecycleService.recordHistory(booking.getId(), BookingStatus.DISPUTED.name(),
                        BookingStatus.IN_PROGRESS.name(), actor.userId(),
                        "Mutual rework agreed via dispute resolution (D-27): " + request.note().trim());
            } else if ("ACCEPT".equals(action)) {
                booking.setStatus(BookingStatus.ACCEPTED.name());
                booking.setAcceptedAt(now);
                booking.setAcceptanceSource("CUSTOMER");
                booking.setUpdatedAt(now);
                BookingEntity accepted = bookingRepository.saveAndFlush(booking);
                lifecycleService.recordHistory(booking.getId(), BookingStatus.DISPUTED.name(),
                        BookingStatus.ACCEPTED.name(), actor.userId(),
                        "Resolved & accepted via dispute resolution: " + request.note().trim());
                lifecycleService.publishDomainEvent(DomainEventContracts.BOOKING_ACCEPTED, accepted);
            }
        }
        publishDisputeEvent(DomainEventContracts.DISPUTE_RESOLVED, saved,
                booking != null ? booking.getRequesterProfileId() : saved.getOpenedByProfileId(), now);
        return toResponse(saved);
    }

    public void publishDisputeEvent(String eventType, DisputeEntity dispute, String requesterProfileId, Instant now) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("disputeId", dispute.getId());
        data.put("bookingId", dispute.getBookingId());
        data.put("requesterProfileId", requesterProfileId);
        data.put("providerId", dispute.getProviderId());
        data.put("status", dispute.getStatus());
        data.put("category", dispute.getCategory());
        data.put("providerNoResponse", dispute.isProviderNoResponse());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId);
        envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, eventType);
        envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
        envelope.put(DomainEventContracts.FIELD_PRODUCER, "booking-service");
        envelope.put("correlationId", dispute.getId());
        envelope.put(DomainEventContracts.FIELD_DATA, data);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize dispute event payload", e);
        }
        outboxRepository.save(BookingOutboxEventEntity.builder()
                .id(eventId)
                .aggregateType("Dispute")
                .aggregateId(dispute.getId())
                .eventType(eventType)
                .routingKey(eventType)
                .payloadJson(payload)
                .status(EntityStatus.PENDING.name())
                .retryCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
    }

    private void linkWorkEvidenceIfProvided(
            UUID disputeId, UUID bookingId, String userId, String role, List<UUID> evidenceIds, Instant now) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return;
        }
        for (UUID evId : evidenceIds) {
            workEvidenceRepository.findById(evId)
                    .filter(ev -> bookingId.equals(ev.getBookingId()))
                    .ifPresent(ev -> evidenceRepository.save(DisputeEvidenceEntity.builder()
                            .id(UUID.randomUUID())
                            .disputeId(disputeId)
                            .uploadedByProfileId(userId)
                            .uploaderRole(role)
                            .workEvidenceId(ev.getId())
                            .bucket(ev.getBucket())
                            .objectKey(ev.getObjectKey())
                            .contentType(ev.getContentType())
                            .sizeBytes(ev.getSizeBytes())
                            .createdAt(now)
                            .build()));
        }
    }

    private void assertCanView(ActorContext actor, DisputeEntity dispute) {
        if (actor.isGlobalAdmin()) {
            return;
        }
        if (dispute.getOpenedByProfileId().equals(actor.userId())) {
            return;
        }
        if (actor.mode() == AccessMode.ORGANIZATION && dispute.getProviderId().equals(actor.organizationId())) {
            return;
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    private String resolveUploaderRole(ActorContext actor, DisputeEntity dispute) {
        if (actor.isGlobalAdmin()) {
            return "ADMIN";
        }
        if (dispute.getOpenedByProfileId().equals(actor.userId())) {
            return "CUSTOMER";
        }
        if (actor.mode() == AccessMode.ORGANIZATION && dispute.getProviderId().equals(actor.organizationId())) {
            return "PROVIDER";
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    private void assertAdmin(ActorContext actor) {
        if (!actor.isGlobalAdmin()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Admin role is required.");
        }
    }

    private DisputeResponse toResponse(DisputeEntity d) {
        List<DisputeEvidenceResponse> evs = evidenceRepository.findByDisputeIdOrderByCreatedAtAsc(d.getId())
                .stream().map(this::toEvidenceResponse).toList();
        return new DisputeResponse(
                d.getId(), d.getBookingId(), d.getOpenedByProfileId(), d.getProviderId(),
                d.getOpenedStage(), d.getCategory(), d.getDescription(), d.getStatus(),
                d.getProviderResponse(), d.getProviderRespondedAt(), d.isProviderNoResponse(),
                d.getSlaDueAt(), d.getSlaReminderStage(), d.getResolutionProposal(),
                d.getResolutionOutcome(), d.getResolvedByAdminId(), d.getResolutionNote(),
                d.getVersion(), d.getCreatedAt(), d.getUpdatedAt(), d.getClosedAt(), evs);
    }

    private DisputeEvidenceResponse toEvidenceResponse(DisputeEvidenceEntity e) {
        String signedUrl = storagePort.createSignedUrl(e.getBucket(), e.getObjectKey(), 300);
        return new DisputeEvidenceResponse(
                e.getId(), e.getDisputeId(), e.getUploadedByProfileId(), e.getUploaderRole(),
                e.getWorkEvidenceId(), e.getContentType(), e.getSizeBytes(), e.getNote(),
                signedUrl, e.getCreatedAt());
    }
}
