package com.homecare.booking.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.entity.BookingPurpose;
import com.homecare.booking.entity.QuotationEntity;
import com.homecare.booking.entity.QuotationItemEntity;
import com.homecare.booking.entity.ServiceRequestEntity;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.QuotationItemRepository;
import com.homecare.booking.repository.QuotationRepository;
import com.homecare.booking.repository.ServiceRequestRepository;
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

    private final ServiceRequestRepository requestRepository;
    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository itemRepository;
    private final BookingOutboxEventRepository outboxRepository;
    private final ServiceRequestService serviceRequestService;
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
            Boolean applyInspectionFeeCredit) {
    }

    public record AcceptQuotationInput(
            @NotNull Integer version) {
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
            Instant acceptedAt,
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

        UUID providerId = (actor.mode() == AccessMode.ORGANIZATION && actor.organizationId() != null)
                ? actor.organizationId()
                : req.getAssignedProviderId();
        if (providerId == null || (req.getAssignedProviderId() != null && !req.getAssignedProviderId().equals(providerId))) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the assigned provider for this request.");
        }

        Instant now = Instant.now();
        Instant validUntil = input.validUntil() != null ? input.validUntil() : now.plus(3, ChronoUnit.DAYS);
        if (!validUntil.isAfter(now)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "validUntil must be in the future.");
        }

        List<QuotationEntity> existingQuotes = quotationRepository.findByServiceRequestIdOrderByVersionDesc(requestId);
        int nextVersion = existingQuotes.isEmpty() ? 1 : existingQuotes.getFirst().getVersion() + 1;
        for (QuotationEntity prev : existingQuotes) {
            if ("SENT".equals(prev.getStatus())) {
                prev.setStatus("SUPERSEDED");
                prev.setUpdatedAt(now);
                quotationRepository.save(prev);
            }
        }

        long itemsTotal = 0L;
        for (QuotationItemInput item : input.items()) {
            if (item.quantity() <= 0 || item.unitPrice() < 0) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Invalid quotation item quantity or unitPrice.");
            }
            itemsTotal = Math.addExact(itemsTotal, Math.multiplyExact((long) item.quantity(), item.unitPrice()));
        }

        long feePaid = req.getInspectionFee();
        boolean creditEnabled = input.applyInspectionFeeCredit() == null || input.applyInspectionFeeCredit();
        long feeCredit = creditEnabled ? Math.min(feePaid, itemsTotal) : 0L;
        long totalDue = Math.max(0L, itemsTotal - feeCredit);

        UUID quoteId = UUID.randomUUID();
        QuotationEntity quote = quotationRepository.saveAndFlush(QuotationEntity.builder()
                .id(quoteId)
                .serviceRequestId(requestId)
                .providerId(providerId)
                .version(nextVersion)
                .currency("VND")
                .itemsTotal(itemsTotal)
                .inspectionFeePaid(feePaid)
                .inspectionFeeCredit(feeCredit)
                .totalDueAfterCredit(totalDue)
                .policyVersion(req.getPolicyVersion())
                .validUntil(validUntil)
                .status("SENT")
                .createdAt(now)
                .updatedAt(now)
                .build());

        List<QuotationItemEntity> savedItems = new ArrayList<>();
        int row = 1;
        for (QuotationItemInput item : input.items()) {
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

        req.setStatus("QUOTATION_SENT");
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

        QuotationEntity latest = quotationRepository.findFirstByServiceRequestIdOrderByVersionDesc(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Quotation not found."));

        Instant now = Instant.now();
        if (quote.getVersion() != input.version()
                || quote.getVersion() != latest.getVersion()
                || !"SENT".equals(quote.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Quotation version is stale or superseded.");
        }
        if (quote.getValidUntil().isBefore(now)) {
            quote.setStatus("EXPIRED");
            quote.setUpdatedAt(now);
            quotationRepository.save(quote);
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
        req.setUpdatedAt(now);
        requestRepository.saveAndFlush(req);

        publishQuotationEvent("quotation.accepted.v1", quote, requesterProfileId, now);
        return toResponse(quote, itemRepository.findByQuotationIdOrderByRowIndexAsc(quote.getId()));
    }

    @Transactional(readOnly = true)
    public List<QuotationResponse> listQuotations(ActorContext actor, UUID requestId) {
        serviceRequestService.getRequest(actor, requestId);
        return quotationRepository.findByServiceRequestIdOrderByVersionDesc(requestId).stream()
                .map(q -> toResponse(q, itemRepository.findByQuotationIdOrderByRowIndexAsc(q.getId())))
                .toList();
    }

    private void publishQuotationEvent(String eventType, QuotationEntity q, String requesterProfileId, Instant now) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quotationId", q.getId());
        data.put("serviceRequestId", q.getServiceRequestId());
        data.put("providerId", q.getProviderId());
        data.put("requesterProfileId", requesterProfileId);
        data.put("version", q.getVersion());
        data.put("totalDueAfterCredit", q.getTotalDueAfterCredit());
        data.put("repairBookingId", q.getRepairBookingId());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId);
        envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, eventType);
        envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
        envelope.put(DomainEventContracts.FIELD_PRODUCER, "booking-service");
        envelope.put("correlationId", q.getServiceRequestId());
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
                q.getAcceptedAt(),
                q.getRepairBookingId(),
                q.getCreatedAt());
    }
}
