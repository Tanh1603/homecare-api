package com.homecare.booking.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.entity.BookingPurpose;
import com.homecare.booking.entity.InspectionFeePolicy;
import com.homecare.booking.entity.QuotationEntity;
import com.homecare.booking.entity.QuotationItemEntity;
import com.homecare.booking.entity.ServiceRequestEntity;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.QuotationItemRepository;
import com.homecare.booking.repository.QuotationRepository;
import com.homecare.booking.repository.ServiceRequestRepository;
import com.homecare.booking.repository.WorkUpdateRepository;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class QuotationService {

    public static final String QUOTE_KIND_INITIAL = "INITIAL";
    public static final String QUOTE_KIND_REVISED = "REVISED";
    public static final long DEFAULT_VALIDITY_HOURS = 48L;
    public static final long MIN_VALIDITY_HOURS = 12L;
    public static final long MAX_VALIDITY_DAYS = 7L;

    private final ServiceRequestRepository requestRepository;
    private final BookingRepository bookingRepository;
    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository itemRepository;
    private final WorkUpdateRepository workUpdateRepository;
    private final BookingOutboxEventRepository outboxRepository;
    private final ServiceRequestService serviceRequestService;
    private final ObjectProvider<WarrantySnapshotService> warrantySnapshotServiceProvider;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    public record QuotationItemInput(
            @NotBlank String type,
            @NotBlank String description,
            @Positive int quantity,
            @PositiveOrZero long unitPrice) {
    }

    public record CreateQuotationInput(
            @NotEmpty List<QuotationItemInput> items,
            Instant validUntil,
            Boolean applyInspectionFeeCredit,
            String supersedeReason,
            Integer workmanshipDurationDays) {

        public CreateQuotationInput(
                List<QuotationItemInput> items,
                Instant validUntil,
                Boolean applyInspectionFeeCredit) {
            this(items, validUntil, applyInspectionFeeCredit, null, null);
        }

        public CreateQuotationInput(
                List<QuotationItemInput> items,
                Instant validUntil,
                Boolean applyInspectionFeeCredit,
                String supersedeReason) {
            this(items, validUntil, applyInspectionFeeCredit, supersedeReason, null);
        }
    }

    public record CreateRevisedQuotationInput(
            @NotEmpty List<QuotationItemInput> items,
            @NotBlank String revisionReason,
            Instant validUntil,
            Integer workmanshipDurationDays) {

        public CreateRevisedQuotationInput(
                List<QuotationItemInput> items,
                String revisionReason,
                Instant validUntil) {
            this(items, revisionReason, validUntil, null);
        }
    }

    public record AcceptQuotationInput(
            @NotNull Integer version) {
    }

    public record RejectQuotationInput(
            @NotBlank String reason) {
    }

    public record QuotationItemResponse(
            UUID id,
            int rowIndex,
            String type,
            String description,
            int quantity,
            long unitPrice,
            long lineTotal) {
    }

    public record QuotationResponse(
            UUID id,
            UUID serviceRequestId,
            UUID bookingId,
            String quoteKind,
            UUID providerId,
            int version,
            String currency,
            List<QuotationItemResponse> items,
            long itemsTotal,
            long inspectionFeePaid,
            long inspectionFeeCredit,
            long totalDueAfterCredit,
            String policyVersion,
            Instant validUntil,
            String status,
            String supersedeReason,
            String revisionReason,
            Instant acceptedAt,
            Instant rejectedAt,
            String rejectionReason,
            UUID repairBookingId,
            Instant createdAt) {
    }

    @Transactional
    public QuotationResponse issueQuotation(ActorContext actor, UUID requestId, CreateQuotationInput input) {
        if (input == null || input.items() == null || input.items().isEmpty()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Quotation items are required.");
        }
        ServiceRequestEntity req = requestRepository.lockById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));

        if ("BOOKED".equals(req.getStatus()) || req.getAcceptedQuotationId() != null) {
            throw new DomainException(CommonErrorCode.CONFLICT, "ServiceRequest already has an accepted quotation.");
        }

        UUID providerId;
        if (actor.isGlobalAdmin() && req.getAssignedProviderId() != null) {
            providerId = req.getAssignedProviderId();
        } else if (actor.mode() == AccessMode.ORGANIZATION && actor.organizationId() != null) {
            providerId = actor.organizationId();
        } else {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Organization context is required to issue a quotation.");
        }
        if (req.getAssignedProviderId() == null || !req.getAssignedProviderId().equals(providerId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the assigned provider for this request.");
        }

        Set<String> rejectedProviders = ServiceRequestService.parseRejectedProviders(req.getRejectedProviderIds());
        if (rejectedProviders.contains(providerId.toString())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Provider quotation was previously rejected for this scope; Customer must adjust scope or re-select first (BR-QUOTE-REJECT-07).");
        }
        if (!Set.of("DIAGNOSTIC_BOOKED", "AWAITING_QUOTATION", "QUOTATION_SENT").contains(req.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "ServiceRequest cannot receive a quotation in status " + req.getStatus() + ".");
        }

        Instant now = Instant.now();
        Instant validUntil = resolveAndValidateValidUntil(input.validUntil(), now);

        List<QuotationEntity> existingQuotes = quotationRepository.findByServiceRequestIdAndQuoteKindOrderByVersionDesc(
                requestId, QUOTE_KIND_INITIAL);
        int nextVersion = existingQuotes.isEmpty() ? 1 : existingQuotes.getFirst().getVersion() + 1;
        boolean hasActiveSent = existingQuotes.stream().anyMatch(q -> "SENT".equals(q.getStatus()));
        if (hasActiveSent && (input.supersedeReason() == null || input.supersedeReason().isBlank())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "supersedeReason is required when superseding an active SENT quotation (D-24).");
        }

        for (QuotationEntity prev : existingQuotes) {
            if ("SENT".equals(prev.getStatus())) {
                prev.setStatus("SUPERSEDED");
                prev.setSupersedeReason(input.supersedeReason().trim());
                prev.setUpdatedAt(now);
                quotationRepository.save(prev);
                publishQuotationEvent(DomainEventContracts.QUOTATION_SUPERSEDED, prev, req.getRequesterProfileId(), now);
            }
        }

        long itemsTotal = computeItemsTotal(input.items());

        // BR-QUOTE-05: Lock inspectionFeeCredit strictly to ServiceRequest's snapshotted inspectionFeePolicy
        long feePaid = req.getInspectionFee();
        InspectionFeePolicy feePolicy = InspectionFeePolicy.parseOrDefault(req.getInspectionFeePolicy(), feePaid);
        long feeCredit = (feePolicy == InspectionFeePolicy.CREDIT_FULL) ? Math.min(feePaid, itemsTotal) : 0L;
        long totalDue = Math.max(0L, itemsTotal - feeCredit);

        if (input.workmanshipDurationDays() != null) {
            WarrantySnapshotService.validateWorkmanshipDuration(req.getServiceCategory(), input.workmanshipDurationDays());
        }

        UUID quoteId = UUID.randomUUID();
        QuotationEntity quote = quotationRepository.saveAndFlush(QuotationEntity.builder()
                .id(quoteId)
                .serviceRequestId(requestId)
                .quoteKind(QUOTE_KIND_INITIAL)
                .providerId(providerId)
                .version(nextVersion)
                .currency("VND")
                .itemsTotal(itemsTotal)
                .inspectionFeePaid(feePaid)
                .inspectionFeeCredit(feeCredit)
                .totalDueAfterCredit(totalDue)
                .workmanshipDurationDays(input.workmanshipDurationDays())
                .policyVersion(req.getPolicyVersion())
                .validUntil(validUntil)
                .status("SENT")
                .createdAt(now)
                .updatedAt(now)
                .build());

        List<QuotationItemEntity> savedItems = persistItems(quoteId, input.items());

        req.setStatus("QUOTATION_SENT");
        req.setStatusChangedAt(now);
        req.setUpdatedAt(now);
        requestRepository.saveAndFlush(req);

        publishQuotationEvent("quotation.sent.v1", quote, req.getRequesterProfileId(), now);
        return toResponse(quote, savedItems);
    }

    @Transactional
    public QuotationResponse acceptQuotation(
            String requesterProfileId,
            UUID requestId,
            UUID quoteId,
            AcceptQuotationInput input) {
        if (input == null || input.version() == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Quotation version is required.");
        }
        ServiceRequestEntity req = requestRepository.lockById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));
        if (!req.getRequesterProfileId().equals(requesterProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this ServiceRequest.");
        }
        if (req.getAcceptedQuotationId() != null || "BOOKED".equals(req.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "ServiceRequest already has an accepted quotation.");
        }

        QuotationEntity quote = quotationRepository.findByIdAndServiceRequestId(quoteId, requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Quotation not found."));

        QuotationEntity latest = quotationRepository.findFirstByServiceRequestIdAndQuoteKindOrderByVersionDesc(
                        requestId, QUOTE_KIND_INITIAL)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Quotation not found."));

        Instant now = Instant.now();
        if (quote.getVersion() != input.version()
                || quote.getVersion() != latest.getVersion()
                || !"SENT".equals(quote.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Quotation version is stale or superseded.");
        }
        if (quote.getValidUntil().isBefore(now)) {
            markQuotationExpiredRequiresNew(quote.getId(), now);
            throw new DomainException(CommonErrorCode.CONFLICT, "Quotation has expired.");
        }

        BookingEntity repairBooking = serviceRequestService.createLinkedBooking(
                req,
                quote.getProviderId(),
                BookingPurpose.REPAIR,
                quote.getTotalDueAfterCredit(),
                now);

        quote.setStatus("ACCEPTED");
        quote.setAcceptedAt(now);
        quote.setRepairBookingId(repairBooking.getId());
        quote.setUpdatedAt(now);
        try {
            quotationRepository.saveAndFlush(quote);
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Quotation already accepted concurrently.");
        }

        req.setAcceptedQuotationId(quote.getId());
        req.setRepairBookingId(repairBooking.getId());
        req.setStatus("BOOKED");
        req.setStatusChangedAt(now);
        req.setUpdatedAt(now);
        requestRepository.saveAndFlush(req);

        WarrantySnapshotService snapshotService = warrantySnapshotServiceProvider != null
                ? warrantySnapshotServiceProvider.getIfAvailable()
                : null;
        if (snapshotService != null) {
            snapshotService.recordQuotationCommitment(
                    repairBooking.getId(), quote, repairBooking.getPurpose(), requesterProfileId);
        }

        publishQuotationEvent("quotation.accepted.v1", quote, requesterProfileId, now);
        return toResponse(quote, itemRepository.findByQuotationIdOrderByRowIndexAsc(quote.getId()));
    }

    @Transactional
    public QuotationResponse rejectQuotation(
            String requesterProfileId,
            UUID requestId,
            UUID quoteId,
            RejectQuotationInput input) {
        if (input == null || input.reason() == null || input.reason().isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Rejection reason is required.");
        }
        ServiceRequestEntity req = requestRepository.lockById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));
        if (!req.getRequesterProfileId().equals(requesterProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this ServiceRequest.");
        }
        QuotationEntity quote = quotationRepository.findByIdAndServiceRequestId(quoteId, requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Quotation not found."));
        if (!"SENT".equals(quote.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Only a SENT quotation can be rejected.");
        }

        Instant now = Instant.now();
        quote.setStatus("REJECTED");
        quote.setRejectedAt(now);
        quote.setRejectionReason(input.reason().trim());
        quote.setUpdatedAt(now);
        quotationRepository.saveAndFlush(quote);

        Set<String> rejectedProviders = ServiceRequestService.parseRejectedProviders(req.getRejectedProviderIds());
        rejectedProviders.add(quote.getProviderId().toString());
        req.setRejectedProviderIds(String.join(",", rejectedProviders));
        req.setStatus("QUOTATION_REJECTED");
        req.setStatusChangedAt(now);
        req.setExpiryWarningNotifiedAt(null);
        req.setUpdatedAt(now);
        requestRepository.saveAndFlush(req);

        publishQuotationEvent(DomainEventContracts.QUOTATION_REJECTED, quote, requesterProfileId, now);
        return toResponse(quote, itemRepository.findByQuotationIdOrderByRowIndexAsc(quote.getId()));
    }

    @Transactional
    public QuotationResponse issueRevisedQuotation(
            ActorContext actor,
            UUID bookingId,
            CreateRevisedQuotationInput input) {
        if (input == null || input.items() == null || input.items().isEmpty()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Revised quotation items are required.");
        }
        if (input.revisionReason() == null || input.revisionReason().isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "revisionReason is required.");
        }
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        assertProviderOrTechForBooking(actor, booking);
        if (!"IN_PROGRESS".equals(booking.getStatus()) && !"ASSIGNED".equals(booking.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Revised Quotation can only be issued for an active ASSIGNED or IN_PROGRESS Booking.");
        }
        Instant now = Instant.now();
        Instant validUntil = resolveAndValidateValidUntil(input.validUntil(), now);
        QuotationEntity quote = createRevisedQuotationInternal(
                booking, input.items(), input.revisionReason().trim(), validUntil, now, input.workmanshipDurationDays());
        return toResponse(quote, itemRepository.findByQuotationIdOrderByRowIndexAsc(quote.getId()));
    }

    @Transactional
    public QuotationEntity issueRevisedQuotationForWorkUpdate(
            BookingEntity booking,
            long extraCost,
            String note,
            Instant now) {
        String reason = (note != null && !note.isBlank()) ? note.trim() : "Phát sinh chi phí trong quá trình thi công";
        List<QuotationItemInput> items = List.of(new QuotationItemInput("OTHER", reason, 1, extraCost));
        return createRevisedQuotationInternal(booking, items, reason, now.plus(DEFAULT_VALIDITY_HOURS, ChronoUnit.HOURS), now, null);
    }

    private QuotationEntity createRevisedQuotationInternal(
            BookingEntity booking,
            List<QuotationItemInput> items,
            String revisionReason,
            Instant validUntil,
            Instant now,
            Integer workmanshipDurationDays) {
        if (workmanshipDurationDays != null) {
            WarrantySnapshotService.validateWorkmanshipDuration(booking.getDeviceType(), workmanshipDurationDays);
        }
        if (quotationRepository.existsByBookingIdAndQuoteKindAndStatus(booking.getId(), QUOTE_KIND_REVISED, "SENT")) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Booking already has a pending SENT Revised Quotation awaiting Customer approval.");
        }
        List<QuotationEntity> existing = quotationRepository.findByBookingIdAndQuoteKindOrderByVersionDesc(
                booking.getId(), QUOTE_KIND_REVISED);
        int nextVersion = existing.isEmpty() ? 1 : existing.getFirst().getVersion() + 1;
        long itemsTotal = computeItemsTotal(items);
        UUID providerId = booking.getProviderId() != null ? booking.getProviderId() : booking.getPreferredProviderId();
        if (providerId == null) {
            providerId = booking.getId();
        }
        UUID quoteId = UUID.randomUUID();
        QuotationEntity quote = quotationRepository.saveAndFlush(QuotationEntity.builder()
                .id(quoteId)
                .serviceRequestId(booking.getServiceRequestId())
                .bookingId(booking.getId())
                .quoteKind(QUOTE_KIND_REVISED)
                .providerId(providerId)
                .version(nextVersion)
                .currency("VND")
                .itemsTotal(itemsTotal)
                .inspectionFeePaid(0L)
                .inspectionFeeCredit(0L)
                .totalDueAfterCredit(itemsTotal)
                .workmanshipDurationDays(workmanshipDurationDays)
                .policyVersion("v1")
                .validUntil(validUntil)
                .status("SENT")
                .revisionReason(revisionReason)
                .repairBookingId(booking.getId())
                .createdAt(now)
                .updatedAt(now)
                .build());
        persistItems(quoteId, items);
        publishQuotationEvent(DomainEventContracts.QUOTATION_REVISED_SENT, quote, booking.getRequesterProfileId(), now);
        return quote;
    }

    @Transactional
    public QuotationResponse acceptRevisedQuotation(
            String requesterProfileId,
            UUID bookingId,
            UUID quoteId,
            AcceptQuotationInput input) {
        if (input == null || input.version() == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Quotation version is required.");
        }
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        if (!booking.getRequesterProfileId().equals(requesterProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this Booking.");
        }
        QuotationEntity quote = quotationRepository.findByIdAndBookingId(quoteId, bookingId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Revised Quotation not found."));
        Instant now = Instant.now();
        if (!QUOTE_KIND_REVISED.equals(quote.getQuoteKind())
                || !"SENT".equals(quote.getStatus())
                || quote.getVersion() != input.version()) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Revised Quotation is stale or not SENT.");
        }
        if (quote.getValidUntil().isBefore(now)) {
            markQuotationExpiredRequiresNew(quote.getId(), now);
            throw new DomainException(CommonErrorCode.CONFLICT, "Revised Quotation has expired.");
        }
        quote.setStatus("ACCEPTED");
        quote.setAcceptedAt(now);
        quote.setUpdatedAt(now);
        quotationRepository.saveAndFlush(quote);

        booking.setQuotedPrice(Math.addExact(booking.getQuotedPrice(), quote.getTotalDueAfterCredit()));
        booking.setUpdatedAt(now);
        bookingRepository.saveAndFlush(booking);

        WarrantySnapshotService snapshotService = warrantySnapshotServiceProvider != null
                ? warrantySnapshotServiceProvider.getIfAvailable()
                : null;
        if (snapshotService != null) {
            snapshotService.recordQuotationCommitment(
                    booking.getId(), quote, booking.getPurpose(), requesterProfileId);
        }

        syncLinkedWorkUpdateStatus(bookingId, quoteId, true, requesterProfileId, now);
        publishQuotationEvent("quotation.accepted.v1", quote, requesterProfileId, now);
        return toResponse(quote, itemRepository.findByQuotationIdOrderByRowIndexAsc(quoteId));
    }

    @Transactional
    public QuotationResponse rejectRevisedQuotation(
            String requesterProfileId,
            UUID bookingId,
            UUID quoteId,
            RejectQuotationInput input) {
        if (input == null || input.reason() == null || input.reason().isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Rejection reason is required.");
        }
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        if (!booking.getRequesterProfileId().equals(requesterProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this Booking.");
        }
        QuotationEntity quote = quotationRepository.findByIdAndBookingId(quoteId, bookingId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Revised Quotation not found."));
        if (!QUOTE_KIND_REVISED.equals(quote.getQuoteKind()) || !"SENT".equals(quote.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Only a SENT Revised Quotation can be rejected.");
        }
        Instant now = Instant.now();
        quote.setStatus("REJECTED");
        quote.setRejectedAt(now);
        quote.setRejectionReason(input.reason().trim());
        quote.setUpdatedAt(now);
        quotationRepository.saveAndFlush(quote);

        syncLinkedWorkUpdateStatus(bookingId, quoteId, false, requesterProfileId, now);
        publishQuotationEvent(DomainEventContracts.QUOTATION_REJECTED, quote, requesterProfileId, now);
        return toResponse(quote, itemRepository.findByQuotationIdOrderByRowIndexAsc(quoteId));
    }

    @Transactional(readOnly = true)
    public List<QuotationResponse> listRevisedQuotations(ActorContext actor, UUID bookingId) {
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        boolean isOwner = booking.getRequesterProfileId().equals(actor.userId());
        boolean isOrg = actor.mode() == AccessMode.ORGANIZATION
                && actor.organizationId() != null
                && actor.organizationId().equals(booking.getProviderId());
        boolean isTech = actor.userId().equals(booking.getAssignedTechnicianProfileId());
        if (!actor.isGlobalAdmin() && !isOwner && !isOrg && !isTech) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        return quotationRepository.findByBookingIdAndQuoteKindOrderByVersionDesc(bookingId, QUOTE_KIND_REVISED).stream()
                .map(q -> toResponse(q, itemRepository.findByQuotationIdOrderByRowIndexAsc(q.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<QuotationResponse> listQuotations(ActorContext actor, UUID requestId) {
        serviceRequestService.getRequest(actor, requestId);
        return quotationRepository.findByServiceRequestIdAndQuoteKindOrderByVersionDesc(requestId, QUOTE_KIND_INITIAL).stream()
                .map(q -> toResponse(q, itemRepository.findByQuotationIdOrderByRowIndexAsc(q.getId())))
                .toList();
    }

    private void syncLinkedWorkUpdateStatus(UUID bookingId, UUID quoteId, boolean approved, String actorId, Instant now) {
        workUpdateRepository.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .filter(wu -> quoteId.equals(wu.getRevisedQuotationId()) && "PENDING_APPROVAL".equals(wu.getExtraCostStatus()))
                .forEach(wu -> {
                    wu.setExtraCostStatus(approved ? "APPROVED" : "REJECTED");
                    wu.setExtraCostApprovedAt(now);
                    wu.setExtraCostApprovedBy(actorId);
                    workUpdateRepository.save(wu);
                });
    }

    private void assertProviderOrTechForBooking(ActorContext actor, BookingEntity booking) {
        boolean isOrg = actor.mode() == AccessMode.ORGANIZATION
                && actor.organizationId() != null
                && actor.organizationId().equals(booking.getProviderId());
        boolean isTech = actor.userId().equals(booking.getAssignedTechnicianProfileId());
        if (!actor.isGlobalAdmin() && !isOrg && !isTech) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Only the assigned Provider or Technician can issue a Revised Quotation.");
        }
    }

    private Instant resolveAndValidateValidUntil(Instant requested, Instant now) {
        Instant validUntil = requested != null ? requested : now.plus(DEFAULT_VALIDITY_HOURS, ChronoUnit.HOURS);
        Duration validity = Duration.between(now, validUntil);
        if (validity.compareTo(Duration.ofHours(MIN_VALIDITY_HOURS)) < 0
                || validity.compareTo(Duration.ofDays(MAX_VALIDITY_DAYS)) > 0) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Quotation validUntil must be between 12 hours and 7 days from now (D-23).");
        }
        return validUntil;
    }

    private long computeItemsTotal(List<QuotationItemInput> items) {
        long itemsTotal = 0L;
        for (QuotationItemInput item : items) {
            if (item.quantity() <= 0 || item.unitPrice() < 0) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Invalid quotation item quantity or unitPrice.");
            }
            itemsTotal = Math.addExact(itemsTotal, Math.multiplyExact((long) item.quantity(), item.unitPrice()));
        }
        return itemsTotal;
    }

    private List<QuotationItemEntity> persistItems(UUID quoteId, List<QuotationItemInput> items) {
        List<QuotationItemEntity> savedItems = new ArrayList<>();
        int row = 1;
        for (QuotationItemInput item : items) {
            long lineTotal = Math.multiplyExact((long) item.quantity(), item.unitPrice());
            savedItems.add(itemRepository.save(QuotationItemEntity.builder()
                    .id(UUID.randomUUID())
                    .quotationId(quoteId)
                    .rowIndex(row++)
                    .itemType(item.type().trim().toUpperCase())
                    .description(item.description().trim())
                    .quantity(item.quantity())
                    .unitPrice(item.unitPrice())
                    .lineTotal(lineTotal)
                    .build()));
        }
        return savedItems;
    }

    public void publishQuotationEvent(String eventType, QuotationEntity q, String requesterProfileId, Instant now) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quotationId", q.getId());
        data.put("serviceRequestId", q.getServiceRequestId());
        data.put("bookingId", q.getBookingId());
        data.put("quoteKind", q.getQuoteKind());
        data.put("providerId", q.getProviderId());
        data.put("requesterProfileId", requesterProfileId);
        data.put("version", q.getVersion());
        data.put("totalDueAfterCredit", q.getTotalDueAfterCredit());
        data.put("repairBookingId", q.getRepairBookingId());
        if (q.getSupersedeReason() != null) {
            data.put("supersedeReason", q.getSupersedeReason());
        }
        if (q.getRevisionReason() != null) {
            data.put("revisionReason", q.getRevisionReason());
        }
        if (q.getRejectionReason() != null) {
            data.put("rejectionReason", q.getRejectionReason());
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId);
        envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, eventType);
        envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
        envelope.put(DomainEventContracts.FIELD_PRODUCER, "booking-service");
        envelope.put("correlationId", q.getServiceRequestId() != null ? q.getServiceRequestId() : q.getBookingId());
        envelope.put(DomainEventContracts.FIELD_DATA, data);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize quotation outbox payload", e);
        }

        outboxRepository.save(BookingOutboxEventEntity.builder()
                .id(eventId)
                .aggregateType("Quotation")
                .aggregateId(q.getId())
                .eventType(eventType)
                .routingKey(eventType)
                .payloadJson(payload)
                .status(EntityStatus.PENDING.name())
                .retryCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
    }

    private QuotationResponse toResponse(QuotationEntity q, List<QuotationItemEntity> items) {
        List<QuotationItemResponse> itemDtos = items.stream()
                .map(i -> new QuotationItemResponse(
                        i.getId(),
                        i.getRowIndex(),
                        i.getItemType(),
                        i.getDescription(),
                        i.getQuantity(),
                        i.getUnitPrice(),
                        i.getLineTotal()))
                .toList();
        return new QuotationResponse(
                q.getId(),
                q.getServiceRequestId(),
                q.getBookingId(),
                q.getQuoteKind(),
                q.getProviderId(),
                q.getVersion(),
                q.getCurrency(),
                itemDtos,
                q.getItemsTotal(),
                q.getInspectionFeePaid(),
                q.getInspectionFeeCredit(),
                q.getTotalDueAfterCredit(),
                q.getPolicyVersion(),
                q.getValidUntil(),
                q.getStatus(),
                q.getSupersedeReason(),
                q.getRevisionReason(),
                q.getAcceptedAt(),
                q.getRejectedAt(),
                q.getRejectionReason(),
                q.getRepairBookingId(),
                q.getCreatedAt());
    }

    private void markQuotationExpiredRequiresNew(UUID quoteId, Instant now) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        QuotationEntity expiredQuote = tx.execute(status -> quotationRepository.findById(quoteId).map(q -> {
            if ("SENT".equals(q.getStatus())) {
                q.setStatus("EXPIRED");
                q.setUpdatedAt(now);
                return quotationRepository.saveAndFlush(q);
            }
            return q;
        }).orElse(null));
        if (expiredQuote == null) {
            return;
        }
        Runnable reconcile = () -> tx.executeWithoutResult(status -> {
            if (QUOTE_KIND_INITIAL.equals(expiredQuote.getQuoteKind()) && expiredQuote.getServiceRequestId() != null) {
                requestRepository.lockById(expiredQuote.getServiceRequestId()).ifPresent(req -> {
                    if ("QUOTATION_SENT".equals(req.getStatus())) {
                        req.setStatus("AWAITING_ALTERNATIVE");
                        req.setFallbackReason("QUOTATION_EXPIRED");
                        req.setStatusChangedAt(now);
                        req.setExpiryWarningNotifiedAt(null);
                        req.setUpdatedAt(now);
                        requestRepository.saveAndFlush(req);
                    }
                });
            } else if (QUOTE_KIND_REVISED.equals(expiredQuote.getQuoteKind()) && expiredQuote.getBookingId() != null) {
                for (var wu : workUpdateRepository.findByBookingIdOrderByCreatedAtAsc(expiredQuote.getBookingId())) {
                    if (quoteId.equals(wu.getRevisedQuotationId())
                            && "PENDING_APPROVAL".equals(wu.getExtraCostStatus())) {
                        wu.setExtraCostStatus("EXPIRED");
                        workUpdateRepository.saveAndFlush(wu);
                    }
                }
            }
        });
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            reconcile.run();
                        }
                    });
        } else {
            reconcile.run();
        }
    }
}
