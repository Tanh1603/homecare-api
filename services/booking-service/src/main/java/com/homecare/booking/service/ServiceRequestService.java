package com.homecare.booking.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.HouseholdVerificationClient.ResolvedServiceAddress;
import com.homecare.booking.client.HouseholdVerificationClient.WarrantyCheckStatus;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.entity.BookingPurpose;
import com.homecare.booking.entity.ServiceRequestEntity;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.ServiceRequestRepository;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.web.PageResponse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ServiceRequestService {

    public static final long MAX_INSPECTION_FEE_VND = 500_000L;

    private final ServiceRequestRepository requestRepository;
    private final BookingRepository bookingRepository;
    private final BookingOutboxEventRepository outboxRepository;
    private final HouseholdVerificationClient householdClient;
    private final ObjectMapper objectMapper;

    public record CreateServiceRequestInput(
            @NotBlank String requestType,
            @NotBlank String serviceCategory,
            UUID serviceOfferingId,
            String priceType,
            Boolean requiresOnsiteSurvey,
            @PositiveOrZero Long inspectionFee,
            UUID deviceId,
            UUID orderLineId,
            UUID warrantyId,
            UUID preferredProviderId,
            UUID serviceAddressId,
            String address,
            String issueDescription,
            Instant preferredStart,
            Instant preferredEnd) {
    }

    public record RejectServiceRequestInput(
            @NotBlank String reason) {
    }

    public record SelectFallbackProviderInput(
            UUID providerId,
            UUID serviceOfferingId,
            String priceType,
            Boolean requiresOnsiteSurvey,
            @PositiveOrZero Long inspectionFee) {
    }

    public record ServiceRequestResponse(
            UUID id,
            String requesterProfileId,
            String requesterName,
            String requestType,
            String serviceCategory,
            UUID serviceOfferingId,
            String priceType,
            boolean requiresOnsiteSurvey,
            long inspectionFee,
            String policyVersion,
            UUID deviceId,
            UUID orderLineId,
            UUID warrantyId,
            String warrantyStatus,
            UUID warrantyResponsibleOrgId,
            UUID assignedProviderId,
            String fallbackReason,
            Instant fallbackNotifiedAt,
            UUID diagnosticBookingId,
            UUID repairBookingId,
            UUID acceptedQuotationId,
            String issueDescription,
            Instant preferredStart,
            Instant preferredEnd,
            String address,
            UUID serviceAddressId,
            String wardCode,
            String wardName,
            String provinceCode,
            String provinceName,
            String formattedAddress,
            String status,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    @Transactional
    public ServiceRequestResponse createRequest(String requesterProfileId, String requesterName, CreateServiceRequestInput input) {
        if (input.serviceAddressId() == null && (input.address() == null || input.address().isBlank())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Either serviceAddressId or address is required.");
        }
        long fee = input.inspectionFee() != null ? input.inspectionFee() : 0L;
        if (fee < 0 || fee > MAX_INSPECTION_FEE_VND) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "inspectionFee out of FeeBounds [0, " + MAX_INSPECTION_FEE_VND + "] VND.");
        }

        ResolvedServiceAddress resolvedAddress = input.serviceAddressId() != null
                ? householdClient.resolveUserAddress(input.serviceAddressId(), requesterProfileId)
                : null;
        String canonicalAddress = resolvedAddress != null
                ? resolvedAddress.formattedAddress()
                : input.address().trim();

        Instant now = Instant.now();
        Instant start = input.preferredStart() != null ? input.preferredStart() : now.plus(2, ChronoUnit.HOURS);
        Instant end = input.preferredEnd() != null ? input.preferredEnd() : start.plus(60, ChronoUnit.MINUTES);
        if (!end.isAfter(start)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "preferredEnd must be after preferredStart.");
        }

        String reqType = input.requestType().trim().toUpperCase();
        String priceType = (input.priceType() != null && !input.priceType().isBlank())
                ? input.priceType().trim().toUpperCase()
                : "FIXED";
        boolean onsite = input.requiresOnsiteSurvey() != null && input.requiresOnsiteSurvey();

        UUID resolvedDeviceId = input.deviceId();
        String warrantyStatus = null;
        UUID warrantyOrgId = null;
        UUID assignedProviderId = input.preferredProviderId();
        String initialStatus = "AWAITING_PROVIDER";

        if ("WARRANTY_CLAIM".equals(reqType) || input.warrantyId() != null) {
            reqType = "WARRANTY_CLAIM";
            WarrantyCheckStatus check = householdClient.checkWarrantyStatus(input.warrantyId(), requesterProfileId);
            if (resolvedDeviceId == null) {
                resolvedDeviceId = check.deviceId();
            }
            warrantyStatus = check.status();
            warrantyOrgId = check.organizationId();
            if (check.expired() || !check.valid()) {
                // Rule 6: Expired or not-covered warranty must NOT silently become free warranty or silent paid repair
                initialStatus = "AWAITING_PAID_REPAIR_CONSENT";
                assignedProviderId = null;
            } else {
                // Rule 1: Initially only the original Warranty Responsible Provider sees the Warranty Request
                assignedProviderId = warrantyOrgId;
                initialStatus = "AWAITING_PROVIDER";
            }
        } else if (resolvedDeviceId != null) {
            householdClient.verifyDeviceOrFallback(resolvedDeviceId, requesterProfileId, input.serviceCategory());
        }

        UUID requestId = UUID.randomUUID();
        ServiceRequestEntity saved = requestRepository.saveAndFlush(ServiceRequestEntity.builder()
                .id(requestId)
                .requesterProfileId(requesterProfileId)
                .requesterName(requesterName)
                .requestType(reqType)
                .serviceCategory(input.serviceCategory().trim())
                .serviceOfferingId(input.serviceOfferingId())
                .priceType(priceType)
                .requiresOnsiteSurvey(onsite)
                .inspectionFee(fee)
                .policyVersion("v1")
                .deviceId(resolvedDeviceId)
                .orderLineId(input.orderLineId())
                .warrantyId(input.warrantyId())
                .warrantyStatus(warrantyStatus)
                .warrantyResponsibleOrgId(warrantyOrgId)
                .assignedProviderId(assignedProviderId)
                .issueDescription(input.issueDescription())
                .preferredStart(start)
                .preferredEnd(end)
                .address(canonicalAddress)
                .serviceAddressId(resolvedAddress != null ? resolvedAddress.addressId() : null)
                .recipientName(resolvedAddress != null ? resolvedAddress.recipientName() : null)
                .phoneNumber(resolvedAddress != null ? resolvedAddress.phoneNumber() : null)
                .addressLine(resolvedAddress != null ? resolvedAddress.addressLine() : null)
                .wardCode(resolvedAddress != null ? resolvedAddress.wardCode() : null)
                .wardName(resolvedAddress != null ? resolvedAddress.wardName() : null)
                .provinceCode(resolvedAddress != null ? resolvedAddress.provinceCode() : null)
                .provinceName(resolvedAddress != null ? resolvedAddress.provinceName() : null)
                .formattedAddress(resolvedAddress != null ? resolvedAddress.formattedAddress() : null)
                .latitude(resolvedAddress != null ? resolvedAddress.latitude() : null)
                .longitude(resolvedAddress != null ? resolvedAddress.longitude() : null)
                .status(initialStatus)
                .createdAt(now)
                .updatedAt(now)
                .build());

        publishEvent("service.request.created.v1", saved, now);
        return toResponse(saved);
    }

    @Transactional
    public ServiceRequestResponse providerAccept(ActorContext actor, UUID requestId, long fixedPriceIfApplicable) {
        ServiceRequestEntity req = requestRepository.lockById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));
        UUID providerId = resolveProviderScope(actor, req.getAssignedProviderId());
        if (!"AWAITING_PROVIDER".equals(req.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "ServiceRequest is not in AWAITING_PROVIDER state.");
        }
        // Rule 1: Warranty request initially strictly scoped to warrantyResponsibleOrgId unless customer already selected fallback
        if ("WARRANTY_CLAIM".equals(req.getRequestType())
                && req.getAssignedProviderId() != null
                && !req.getAssignedProviderId().equals(providerId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Only the assigned Warranty Responsible Provider can accept this request.");
        }

        Instant now = Instant.now();
        req.setAssignedProviderId(providerId);

        if ("FIXED".equalsIgnoreCase(req.getPriceType()) || "WARRANTY_CLAIM".equalsIgnoreCase(req.getRequestType())) {
            long price = "WARRANTY_CLAIM".equalsIgnoreCase(req.getRequestType()) ? 0L : Math.max(0L, fixedPriceIfApplicable);
            BookingEntity repairBooking = createLinkedBooking(req, providerId, BookingPurpose.REPAIR, price, now);
            req.setRepairBookingId(repairBooking.getId());
            req.setStatus("BOOKED");
        } else if (req.isRequiresOnsiteSurvey()) {
            BookingEntity diagnosticBooking = createLinkedBooking(
                    req, providerId, BookingPurpose.DIAGNOSTIC, req.getInspectionFee(), now);
            req.setDiagnosticBookingId(diagnosticBooking.getId());
            req.setStatus("DIAGNOSTIC_BOOKED");
        } else {
            req.setStatus("AWAITING_QUOTATION");
        }

        req.setUpdatedAt(now);
        ServiceRequestEntity saved = requestRepository.saveAndFlush(req);
        publishEvent("service.request.provider.matched.v1", saved, now);
        return toResponse(saved);
    }

    @Transactional
    public ServiceRequestResponse triggerWarrantyFallback(ActorContext actor, UUID requestId, String reason) {
        ServiceRequestEntity req = requestRepository.lockById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));
        if (actor.mode() == AccessMode.ORGANIZATION
                && req.getAssignedProviderId() != null
                && !req.getAssignedProviderId().equals(actor.organizationId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        Instant now = Instant.now();
        req.setFallbackReason(reason != null && !reason.isBlank() ? reason.trim() : "PROVIDER_REJECTED");
        req.setFallbackNotifiedAt(now);
        // Rule 3, 4, 5: Notify Customer and wait for Customer to choose another Provider; do NOT auto-transfer
        req.setStatus("AWAITING_WARRANTY_FALLBACK_CONSENT");
        req.setUpdatedAt(now);
        ServiceRequestEntity saved = requestRepository.saveAndFlush(req);
        publishEvent("warranty.request.fallback.available.v1", saved, now);
        return toResponse(saved);
    }

    @Transactional
    public ServiceRequestResponse customerSelectFallbackProvider(
            String requesterProfileId,
            UUID requestId,
            SelectFallbackProviderInput input) {
        ServiceRequestEntity req = requestRepository.lockById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));
        if (!req.getRequesterProfileId().equals(requesterProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        if (!"AWAITING_WARRANTY_FALLBACK_CONSENT".equals(req.getStatus())
                && !"AWAITING_PAID_REPAIR_CONSENT".equals(req.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "ServiceRequest is not awaiting fallback or paid repair consent.");
        }
        if (input.providerId() == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "providerId is required.");
        }
        long fee = input.inspectionFee() != null ? input.inspectionFee() : req.getInspectionFee();
        if (fee < 0 || fee > MAX_INSPECTION_FEE_VND) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "inspectionFee out of bounds.");
        }
        if ("AWAITING_PAID_REPAIR_CONSENT".equals(req.getStatus())) {
            // Rule 6: Customer explicitly consents to switch expired/uncovered warranty to PAID_REPAIR
            req.setRequestType("PAID_REPAIR");
        }
        req.setAssignedProviderId(input.providerId());
        if (input.serviceOfferingId() != null) {
            req.setServiceOfferingId(input.serviceOfferingId());
        }
        if (input.priceType() != null && !input.priceType().isBlank()) {
            req.setPriceType(input.priceType().trim().toUpperCase());
        }
        if (input.requiresOnsiteSurvey() != null) {
            req.setRequiresOnsiteSurvey(input.requiresOnsiteSurvey());
        }
        req.setInspectionFee(fee);
        req.setStatus("AWAITING_PROVIDER");
        req.setUpdatedAt(Instant.now());
        return toResponse(requestRepository.saveAndFlush(req));
    }

    @Transactional(readOnly = true)
    public PageResponse<ServiceRequestResponse> listRequests(ActorContext actor, int page, int size) {
        PageResponse.validate(page, size);
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by("createdAt").descending());
        Page<ServiceRequestEntity> result;
        if (actor.mode() == AccessMode.ORGANIZATION && actor.organizationId() != null) {
            // Rule 1: Org only sees requests assigned to its organizationId
            result = requestRepository.findByAssignedProviderIdOrderByCreatedAtDesc(actor.organizationId(), pageable);
        } else {
            result = requestRepository.findByRequesterProfileIdOrderByCreatedAtDesc(actor.userId(), pageable);
        }
        return PageResponse.of(result.map(this::toResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ServiceRequestResponse getRequest(ActorContext actor, UUID requestId) {
        ServiceRequestEntity req = requestRepository.findById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));
        if (actor.mode() == AccessMode.ORGANIZATION) {
            if (req.getAssignedProviderId() == null || !req.getAssignedProviderId().equals(actor.organizationId())) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
        } else if (!actor.isGlobalAdmin() && !req.getRequesterProfileId().equals(actor.userId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        return toResponse(req);
    }

    public BookingEntity createLinkedBooking(
            ServiceRequestEntity req,
            UUID providerId,
            BookingPurpose purpose,
            long quotedPrice,
            Instant now) {
        UUID serviceId = req.getServiceOfferingId() != null ? req.getServiceOfferingId() : req.getId();
        return bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(req.getRequesterProfileId())
                .requesterName(req.getRequesterName())
                .serviceId(serviceId)
                .serviceName(req.getServiceCategory() + " (" + purpose.name() + ")")
                .providerId(providerId)
                .preferredProviderId(providerId)
                .bookingType("WARRANTY_CLAIM".equals(req.getRequestType()) ? "WARRANTY_CLAIM" : "STANDARD")
                .serviceRequestId(req.getId())
                .purpose(purpose.name())
                .orderLineId(req.getOrderLineId())
                .warrantyId(req.getWarrantyId())
                .deviceId(req.getDeviceId())
                .deviceType(req.getServiceCategory())
                .preferredStart(req.getPreferredStart())
                .preferredEnd(req.getPreferredEnd())
                .address(req.getAddress())
                .serviceAddressId(req.getServiceAddressId())
                .recipientName(req.getRecipientName())
                .phoneNumber(req.getPhoneNumber())
                .addressLine(req.getAddressLine())
                .wardCode(req.getWardCode())
                .wardName(req.getWardName())
                .provinceCode(req.getProvinceCode())
                .provinceName(req.getProvinceName())
                .formattedAddress(req.getFormattedAddress())
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .notes(req.getIssueDescription())
                .status("REQUESTED")
                .allocationStatus("PENDING")
                .quotedPrice(quotedPrice)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private UUID resolveProviderScope(ActorContext actor, UUID fallbackAssignedProviderId) {
        if (actor.mode() == AccessMode.ORGANIZATION && actor.organizationId() != null) {
            return actor.organizationId();
        }
        if (fallbackAssignedProviderId != null) {
            return fallbackAssignedProviderId;
        }
        throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Provider organization context is required.");
    }

    public void publishEvent(String eventType, ServiceRequestEntity req, Instant now) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serviceRequestId", req.getId());
        data.put("requesterProfileId", req.getRequesterProfileId());
        data.put("requestType", req.getRequestType());
        data.put("serviceCategory", req.getServiceCategory());
        data.put("assignedProviderId", req.getAssignedProviderId());
        data.put("status", req.getStatus());
        data.put("fallbackReason", req.getFallbackReason());
        data.put("deviceId", req.getDeviceId());
        data.put("warrantyId", req.getWarrantyId());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId);
        envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, eventType);
        envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
        envelope.put(DomainEventContracts.FIELD_PRODUCER, "booking-service");
        envelope.put("correlationId", req.getId());
        envelope.put(DomainEventContracts.FIELD_DATA, data);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize service request outbox payload", e);
        }

        outboxRepository.save(BookingOutboxEventEntity.builder()
                .id(eventId)
                .aggregateType("ServiceRequest")
                .aggregateId(req.getId())
                .eventType(eventType)
                .routingKey(eventType)
                .payloadJson(payload)
                .status(EntityStatus.PENDING.name())
                .retryCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
    }

    public ServiceRequestResponse toResponse(ServiceRequestEntity r) {
        return new ServiceRequestResponse(
                r.getId(),
                r.getRequesterProfileId(),
                r.getRequesterName(),
                r.getRequestType(),
                r.getServiceCategory(),
                r.getServiceOfferingId(),
                r.getPriceType(),
                r.isRequiresOnsiteSurvey(),
                r.getInspectionFee(),
                r.getPolicyVersion(),
                r.getDeviceId(),
                r.getOrderLineId(),
                r.getWarrantyId(),
                r.getWarrantyStatus(),
                r.getWarrantyResponsibleOrgId(),
                r.getAssignedProviderId(),
                r.getFallbackReason(),
                r.getFallbackNotifiedAt(),
                r.getDiagnosticBookingId(),
                r.getRepairBookingId(),
                r.getAcceptedQuotationId(),
                r.getIssueDescription(),
                r.getPreferredStart(),
                r.getPreferredEnd(),
                r.getAddress(),
                r.getServiceAddressId(),
                r.getWardCode(),
                r.getWardName(),
                r.getProvinceCode(),
                r.getProvinceName(),
                r.getFormattedAddress(),
                r.getStatus(),
                r.getVersion(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
