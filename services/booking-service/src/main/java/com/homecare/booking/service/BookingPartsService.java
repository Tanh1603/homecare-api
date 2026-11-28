package com.homecare.booking.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.booking.dto.PartsDtos.CreatePartsRequest;
import com.homecare.booking.dto.PartsDtos.PartsRequestResponse;
import com.homecare.booking.dto.PartsDtos.PartsUsageResponse;
import com.homecare.booking.dto.PartsDtos.RecordPartsUsageRequest;
import com.homecare.booking.dto.PartsDtos.RefuseCustomerPartRequest;
import com.homecare.booking.dto.PartsDtos.ReviewPartsRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.entity.BookingPartsRequestEntity;
import com.homecare.booking.entity.BookingPartsUsageEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingPartsRequestRepository;
import com.homecare.booking.repository.BookingPartsUsageRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.security.BookingAccessPolicy;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.web.EtagSupport;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Lean v0.1 Parts Procurement Tracking service (D-19, BR-PART-01..04, BR-WARR-07).
 * Strictly zero commerce-service Order/Inventory coupling.
 */
@Service
@RequiredArgsConstructor
public class BookingPartsService {

    public static final Set<String> VALID_PART_SOURCES = Set.of(
            "PROVIDER_INVENTORY", "PROVIDER_SUPPLIER", "EXTERNAL_SUPPLIER", "CUSTOMER_SUPPLIED");
    public static final Set<String> VALID_PARTS_WARRANTY_STATUS = Set.of("AVAILABLE", "NONE", "UNVERIFIED");
    public static final Set<String> VALID_PARTS_WARRANTY_ISSUERS = Set.of("PROVIDER", "SUPPLIER", "MANUFACTURER");
    private static final Set<String> ACTIVE_EXECUTION_STATUSES = Set.of(
            BookingStatus.IN_PROGRESS.name(),
            BookingStatus.WAITING_FOR_PARTS.name(),
            BookingStatus.REVISION_REQUESTED.name());

    private final BookingRepository bookingRepository;
    private final BookingPartsRequestRepository partsRequestRepository;
    private final BookingPartsUsageRepository partsUsageRepository;
    private final BookingOutboxEventRepository outboxRepository;
    private final BookingAccessPolicy accessPolicy;
    private final BookingLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;

    @Transactional
    public PartsRequestResponse createPartsRequest(ActorContext actor, UUID bookingId, CreatePartsRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        assertCanMutateParts(actor, booking);
        if (!ACTIVE_EXECUTION_STATUSES.contains(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Parts requests can only be created during IN_PROGRESS, WAITING_FOR_PARTS, or REVISION_REQUESTED.");
        }
        if (booking.getProviderId() == null) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Booking has no assigned provider.");
        }
        String source = normalizePartSource(request.partSource());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        boolean needsApproval = "EXTERNAL_SUPPLIER".equals(source);
        boolean isProviderManager = isOrgManagerOrAdmin(actor, booking);

        String initialStatus = (needsApproval && !isProviderManager)
                ? "PENDING_PROVIDER_APPROVAL"
                : "PROVIDER_APPROVED";

        BookingPartsRequestEntity saved = partsRequestRepository.saveAndFlush(BookingPartsRequestEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .providerId(booking.getProviderId())
                .requestedByProfileId(actor.userId())
                .partName(request.partName().trim())
                .partCode(trimOrNull(request.partCode()))
                .quantity(request.quantity())
                .partSource(source)
                .supplierName(trimOrNull(request.supplierName()))
                .costPrice(isProviderManager ? request.costPrice() : null)
                .unitPrice(request.unitPrice())
                .status(initialStatus)
                .compatibilityNote(trimOrNull(request.compatibilityNote()))
                .evidenceRef(trimOrNull(request.evidenceRef()))
                .approvedByProfileId("PROVIDER_APPROVED".equals(initialStatus) ? actor.userId() : null)
                .approvedAt("PROVIDER_APPROVED".equals(initialStatus) ? now : null)
                .createdAt(now)
                .updatedAt(now)
                .build());

        if ("PENDING_PROVIDER_APPROVAL".equals(initialStatus)) {
            publishPartsEvent(DomainEventContracts.PARTS_REQUEST_CREATED, saved, now);
        }
        return toRequestResponse(saved, canSeeCostPrice(actor, booking));
    }

    @Transactional(readOnly = true)
    public List<PartsRequestResponse> listPartsRequests(ActorContext actor, UUID bookingId) {
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        if (!accessPolicy.isVisible(actor, booking)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        boolean includeCostPrice = canSeeCostPrice(actor, booking);
        return partsRequestRepository.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                .map(r -> toRequestResponse(r, includeCostPrice))
                .toList();
    }

    @Transactional
    public PartsRequestResponse reviewPartsRequest(
            ActorContext actor,
            UUID bookingId,
            UUID requestId,
            String ifMatch,
            ReviewPartsRequest request) {
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        if (!isOrgManagerOrAdmin(actor, booking)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Only Provider ORG_OWNER/ORG_ADMIN or Admin can approve/reject EXTERNAL_SUPPLIER parts requests (BR-PART-01).");
        }
        BookingPartsRequestEntity entity = partsRequestRepository.lockById(requestId)
                .filter(r -> bookingId.equals(r.getBookingId()))
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Parts request not found."));
        EtagSupport.verifyIfMatch(ifMatch, entity.getVersion());
        if (!"PENDING_PROVIDER_APPROVAL".equals(entity.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Only PENDING_PROVIDER_APPROVAL requests can be reviewed.");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (Boolean.TRUE.equals(request.approved())) {
            entity.setStatus("PROVIDER_APPROVED");
            if (request.costPrice() != null) {
                entity.setCostPrice(request.costPrice());
            }
            if (request.unitPrice() != null) {
                entity.setUnitPrice(request.unitPrice());
            }
            entity.setApprovedByProfileId(actor.userId());
            entity.setApprovedAt(now);
            entity.setRefusalReason(null);
        } else {
            if (request.reason() == null || request.reason().isBlank()) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                        "Rejection reason is required when rejecting a parts request.");
            }
            entity.setStatus("PROVIDER_REJECTED");
            entity.setRefusalReason(request.reason().trim());
            entity.setApprovedByProfileId(actor.userId());
            entity.setApprovedAt(now);
        }
        entity.setUpdatedAt(now);
        BookingPartsRequestEntity saved = partsRequestRepository.saveAndFlush(entity);
        publishPartsEvent(DomainEventContracts.PARTS_REQUEST_REVIEWED, saved, now);
        return toRequestResponse(saved, true);
    }

    @Transactional
    public PartsRequestResponse refuseCustomerPart(
            ActorContext actor,
            UUID bookingId,
            UUID requestId,
            String ifMatch,
            RefuseCustomerPartRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        assertCanMutateParts(actor, booking);
        if (!actor.isGlobalAdmin()
                && (booking.getAssignedTechnicianProfileId() == null
                        || !booking.getAssignedTechnicianProfileId().equals(actor.userId()))) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Only the assigned technician can refuse a customer-supplied part (BR-PART-03).");
        }
        BookingPartsRequestEntity entity = partsRequestRepository.lockById(requestId)
                .filter(r -> bookingId.equals(r.getBookingId()))
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Parts request not found."));
        EtagSupport.verifyIfMatch(ifMatch, entity.getVersion());
        if (!"CUSTOMER_SUPPLIED".equals(entity.getPartSource())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Compatibility refusal applies only to CUSTOMER_SUPPLIED parts (BR-PART-03).");
        }
        if ("USED".equals(entity.getStatus()) || "CANCELLED".equals(entity.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Cannot refuse a USED or CANCELLED parts request.");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        entity.setStatus("CUSTOMER_PART_REFUSED");
        entity.setRefusalReason(request.refusalReason().trim());
        if (request.evidenceRef() != null && !request.evidenceRef().isBlank()) {
            entity.setEvidenceRef(request.evidenceRef().trim());
        }
        entity.setUpdatedAt(now);
        BookingPartsRequestEntity saved = partsRequestRepository.saveAndFlush(entity);

        // D-30 CAND-BD-05: Move IN_PROGRESS booking to WAITING_FOR_PARTS on CUSTOMER_SUPPLIED refusal
        if (BookingStatus.IN_PROGRESS.name().equals(booking.getStatus())) {
            String previous = booking.getStatus();
            lifecycleService.releaseActiveAllocationIfPresent(
                    booking, "CUSTOMER_PART_REFUSED: " + request.refusalReason().trim());
            booking.setStatus(BookingStatus.WAITING_FOR_PARTS.name());
            booking.setWaitingForPartsSince(now);
            booking.setWaitingForPartsReminderStage(0);
            booking.setUpdatedAt(now);
            BookingEntity savedBooking = bookingRepository.saveAndFlush(booking);
            lifecycleService.recordHistory(bookingId, previous, savedBooking.getStatus(), actor.userId(),
                    "CUSTOMER_SUPPLIED part refused: " + request.refusalReason().trim());
            lifecycleService.publishDomainEvent(DomainEventContracts.BOOKING_WAITING_FOR_PARTS, savedBooking);
        } else {
            lifecycleService.recordHistory(bookingId, booking.getStatus(), booking.getStatus(), actor.userId(),
                    "CUSTOMER_SUPPLIED part refused: " + request.refusalReason().trim());
        }
        return toRequestResponse(saved, canSeeCostPrice(actor, booking));
    }

    @Transactional
    public PartsRequestResponse cancelPartsRequest(
            ActorContext actor,
            UUID bookingId,
            UUID requestId,
            String ifMatch,
            String reason) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        assertCanMutateParts(actor, booking);
        BookingPartsRequestEntity entity = partsRequestRepository.lockById(requestId)
                .filter(r -> bookingId.equals(r.getBookingId()))
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Parts request not found."));
        EtagSupport.verifyIfMatch(ifMatch, entity.getVersion());
        if ("USED".equals(entity.getStatus()) || "CANCELLED".equals(entity.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Cannot cancel a USED or already CANCELLED parts request.");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        entity.setStatus("CANCELLED");
        if (reason != null && !reason.isBlank()) {
            entity.setRefusalReason(reason.trim());
        }
        entity.setUpdatedAt(now);
        BookingPartsRequestEntity saved = partsRequestRepository.saveAndFlush(entity);
        return toRequestResponse(saved, canSeeCostPrice(actor, booking));
    }

    @Transactional
    public PartsUsageResponse recordPartsUsage(ActorContext actor, UUID bookingId, RecordPartsUsageRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        assertCanMutateParts(actor, booking);
        if (!ACTIVE_EXECUTION_STATUSES.contains(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Parts usage can only be recorded while booking is IN_PROGRESS, WAITING_FOR_PARTS, or REVISION_REQUESTED.");
        }

        String source = normalizePartSource(request.partSource());
        BookingPartsRequestEntity linkedRequest = null;
        if (request.partsRequestId() != null) {
            linkedRequest = partsRequestRepository.lockById(request.partsRequestId())
                    .filter(r -> bookingId.equals(r.getBookingId()))
                    .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Parts request not found."));
            if (!"PROVIDER_APPROVED".equals(linkedRequest.getStatus())) {
                throw new DomainException(CommonErrorCode.CONFLICT,
                        "Parts request must be PROVIDER_APPROVED before recording installation (BR-PART-02).");
            }
        } else if ("EXTERNAL_SUPPLIER".equals(source)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "EXTERNAL_SUPPLIER usage requires an approved partsRequestId (BR-PART-02).");
        }

        String warrantyStatus = normalizeWarrantyStatus(request.partsWarrantyStatus(), source);
        String warrantyIssuer = trimOrNull(request.warrantyIssuer());
        if (warrantyIssuer != null) {
            warrantyIssuer = warrantyIssuer.toUpperCase();
            if (!VALID_PARTS_WARRANTY_ISSUERS.contains(warrantyIssuer)) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Invalid parts warrantyIssuer.");
            }
        }
        String evidenceRef = trimOrNull(request.evidenceRef());
        if ("AVAILABLE".equals(warrantyStatus)) {
            if (warrantyIssuer == null || evidenceRef == null) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                        "warrantyIssuer and evidenceRef are required when partsWarrantyStatus = AVAILABLE (BR-WARR-07).");
            }
        }

        long unitPrice = "CUSTOMER_SUPPLIED".equals(source) ? 0L : (request.unitPrice() != null ? request.unitPrice() : 0L);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        BookingPartsUsageEntity saved = partsUsageRepository.saveAndFlush(BookingPartsUsageEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .partsRequestId(linkedRequest != null ? linkedRequest.getId() : null)
                .partName(request.partName().trim())
                .partCode(trimOrNull(request.partCode()))
                .quantity(request.quantity())
                .partSource(source)
                .unitPrice(unitPrice)
                .partsWarrantyStatus(warrantyStatus)
                .warrantyIssuer(warrantyIssuer)
                .warrantyDurationDays(request.warrantyDurationDays())
                .warrantyTerms(trimOrNull(request.warrantyTerms()))
                .evidenceRef(evidenceRef)
                .purchaseProofRef(trimOrNull(request.purchaseProofRef()))
                .recordedByProfileId(actor.userId())
                .createdAt(now)
                .build());

        if (linkedRequest != null) {
            linkedRequest.setStatus("USED");
            linkedRequest.setUpdatedAt(now);
            partsRequestRepository.saveAndFlush(linkedRequest);
        }
        return toUsageResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PartsUsageResponse> listPartsUsage(ActorContext actor, UUID bookingId) {
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        if (!accessPolicy.isVisible(actor, booking)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        return partsUsageRepository.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .map(this::toUsageResponse)
                .toList();
    }

    private void assertCanMutateParts(ActorContext actor, BookingEntity booking) {
        if (isOrgManagerOrAdmin(actor, booking)) {
            return;
        }
        accessPolicy.assertCanAppendWorkUpdate(actor, booking);
    }

    private boolean isOrgManagerOrAdmin(ActorContext actor, BookingEntity booking) {
        if (actor.isGlobalAdmin()) {
            return true;
        }
        return actor.mode() == AccessMode.ORGANIZATION
                && actor.organizationId() != null
                && actor.organizationId().equals(booking.getProviderId())
                && actor.hasOrgRole("ORG_OWNER", "ORG_ADMIN");
    }

    private boolean canSeeCostPrice(ActorContext actor, BookingEntity booking) {
        if (actor.isGlobalAdmin()) {
            return true;
        }
        return actor.mode() == AccessMode.ORGANIZATION
                && actor.organizationId() != null
                && actor.organizationId().equals(booking.getProviderId());
    }

    private String normalizePartSource(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase();
        if (!VALID_PART_SOURCES.contains(normalized)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "partSource must be one of PROVIDER_INVENTORY, PROVIDER_SUPPLIER, EXTERNAL_SUPPLIER, CUSTOMER_SUPPLIED.");
        }
        return normalized;
    }

    private String normalizeWarrantyStatus(String raw, String source) {
        if (raw == null || raw.isBlank()) {
            return "CUSTOMER_SUPPLIED".equals(source) ? "NONE" : "UNVERIFIED";
        }
        String normalized = raw.trim().toUpperCase();
        if (!VALID_PARTS_WARRANTY_STATUS.contains(normalized)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "partsWarrantyStatus must be AVAILABLE, NONE, or UNVERIFIED.");
        }
        return normalized;
    }

    private String trimOrNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private void publishPartsEvent(String eventType, BookingPartsRequestEntity req, Instant now) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("partsRequestId", req.getId().toString());
            data.put("bookingId", req.getBookingId().toString());
            data.put("providerId", req.getProviderId().toString());
            data.put("requestedByProfileId", req.getRequestedByProfileId());
            data.put("partName", req.getPartName());
            data.put("partSource", req.getPartSource());
            data.put("status", req.getStatus());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString());
            envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, eventType);
            envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
            envelope.put(DomainEventContracts.FIELD_PRODUCER, "booking-service");
            envelope.put(DomainEventContracts.FIELD_DATA, data);

            outboxRepository.save(BookingOutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("BOOKING")
                    .aggregateId(req.getBookingId())
                    .eventType(eventType)
                    .routingKey(eventType)
                    .payloadJson(objectMapper.writeValueAsString(envelope))
                    .status(EntityStatus.PENDING.name())
                    .retryCount(0)
                    .createdAt(now)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist parts outbox event " + eventType, ex);
        }
    }

    private PartsRequestResponse toRequestResponse(BookingPartsRequestEntity e, boolean includeCostPrice) {
        return new PartsRequestResponse(
                e.getId(), e.getBookingId(), e.getProviderId(), e.getRequestedByProfileId(),
                e.getPartName(), e.getPartCode(), e.getQuantity(), e.getPartSource(),
                e.getSupplierName(), includeCostPrice ? e.getCostPrice() : null, e.getUnitPrice(),
                e.getStatus(), e.getCompatibilityNote(), e.getRefusalReason(), e.getEvidenceRef(),
                e.getApprovedByProfileId(), e.getApprovedAt(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private PartsUsageResponse toUsageResponse(BookingPartsUsageEntity e) {
        return new PartsUsageResponse(
                e.getId(), e.getBookingId(), e.getPartsRequestId(), e.getPartName(), e.getPartCode(),
                e.getQuantity(), e.getPartSource(), e.getUnitPrice(), e.getPartsWarrantyStatus(),
                e.getWarrantyIssuer(), e.getWarrantyDurationDays(), e.getWarrantyTerms(),
                e.getEvidenceRef(), e.getPurchaseProofRef(), e.getRecordedByProfileId(), e.getCreatedAt());
    }
}
