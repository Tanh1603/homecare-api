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
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import org.springframework.beans.factory.ObjectProvider;
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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import com.homecare.booking.entity.InspectionFeePolicy;
import com.homecare.booking.entity.QuotationEntity;
import com.homecare.booking.repository.QuotationRepository;

@Service
@RequiredArgsConstructor
public class ServiceRequestService {

    public static final int MAX_FALLBACK_ROUNDS = 3;

    @Value("${homecare.policy.inspection-fee-warning-vnd:500000}")
    private long inspectionFeeWarningThresholdVnd = 500_000L;

    private final ServiceRequestRepository requestRepository;
    private final QuotationRepository quotationRepository;
    private final BookingRepository bookingRepository;
    private final BookingOutboxEventRepository outboxRepository;
    private final HouseholdVerificationClient householdClient;
    private final ObjectProvider<ProviderGrpcClientAdapter> providerGrpcClientProvider;
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
            Instant preferredEnd,
            String inspectionFeePolicy) {

        public CreateServiceRequestInput(
                String requestType,
                String serviceCategory,
                UUID serviceOfferingId,
                String priceType,
                Boolean requiresOnsiteSurvey,
                Long inspectionFee,
                UUID deviceId,
                UUID orderLineId,
                UUID warrantyId,
                UUID preferredProviderId,
                UUID serviceAddressId,
                String address,
                String issueDescription,
                Instant preferredStart,
                Instant preferredEnd) {
            this(requestType, serviceCategory, serviceOfferingId, priceType, requiresOnsiteSurvey,
                    inspectionFee, deviceId, orderLineId, warrantyId, preferredProviderId,
                    serviceAddressId, address, issueDescription, preferredStart, preferredEnd, null);
        }
    }

    public record RejectServiceRequestInput(
            @NotBlank String reason) {
    }

    public record CancelServiceRequestInput(
            String reason) {
    }

    public record AdjustScopeInput(
            String issueDescription,
            Instant preferredStart,
            Instant preferredEnd,
            UUID providerId) {
    }

    public record SelectFallbackProviderInput(
            UUID providerId,
            UUID serviceOfferingId,
            String priceType,
            Boolean requiresOnsiteSurvey,
            @PositiveOrZero Long inspectionFee,
            String inspectionFeePolicy) {

        public SelectFallbackProviderInput(
                UUID providerId,
                UUID serviceOfferingId,
                String priceType,
                Boolean requiresOnsiteSurvey,
                Long inspectionFee) {
            this(providerId, serviceOfferingId, priceType, requiresOnsiteSurvey, inspectionFee, null);
        }
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
            String inspectionFeePolicy,
            boolean inspectionFeeWarningFlag,
            int fallbackCount,
            String cancelReason,
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
        if (fee < 0) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "inspectionFee must be >= 0.");
        }
        InspectionFeePolicy feePolicy = resolveFeePolicy(input.inspectionFeePolicy(), fee);
        boolean feeWarningFlag = fee > inspectionFeeWarningThresholdVnd;

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
        boolean isWarranty = "WARRANTY_CLAIM".equals(reqType) || input.warrantyId() != null;
        if (!isWarranty && assignedProviderId == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "preferredProviderId is required for directed 1-to-1 ServiceRequest (D-22).");
        }
        if (assignedProviderId != null && providerGrpcClientProvider != null) {
            ProviderGrpcClientAdapter grpc = providerGrpcClientProvider.getIfAvailable();
            if (grpc != null) {
                if (grpc.isUserMemberOfProvider(assignedProviderId, requesterProfileId)) {
                    throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN,
                            "Active organization members cannot create a personal service request for their own organization (D-29).");
                }
                if (input.serviceOfferingId() != null) {
                    var offering = grpc.verifyOffering(input.serviceOfferingId());
                    if (offering != null) {
                        if (!offering.getActive()) {
                            throw new DomainException(CommonErrorCode.CONFLICT, "Selected service offering is not active.");
                        }
                        if (!offering.getProviderId().isBlank()
                                && !assignedProviderId.toString().equals(offering.getProviderId())) {
                            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                                    "serviceOfferingId does not belong to preferredProviderId.");
                        }
                    }
                }
            }
        }
        String initialStatus = "AWAITING_PROVIDER";

        if (isWarranty) {
            reqType = "WARRANTY_CLAIM";
            WarrantyCheckStatus check = householdClient.checkWarrantyStatus(input.warrantyId(), requesterProfileId);
            if (resolvedDeviceId == null) {
                resolvedDeviceId = check.deviceId();
            }
            warrantyStatus = check.status();
            warrantyOrgId = check.organizationId();
            if (check.expired() || !check.valid()) {
                initialStatus = "AWAITING_PAID_REPAIR_CONSENT";
                assignedProviderId = null;
            } else {
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
                .inspectionFeePolicy(feePolicy.name())
                .inspectionFeeWarningFlag(feeWarningFlag)
                .fallbackCount(0)
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
                .statusChangedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());

        publishEvent("service.request.created.v1", saved, now);
        return toResponse(saved);
    }

    private InspectionFeePolicy resolveFeePolicy(String rawPolicy, long fee) {
        try {
            InspectionFeePolicy parsed = InspectionFeePolicy.parseOrDefault(rawPolicy, fee);
            if (fee == 0L && parsed != InspectionFeePolicy.NO_FEE && (rawPolicy == null || rawPolicy.isBlank())) {
                return InspectionFeePolicy.NO_FEE;
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "inspectionFeePolicy must be NO_FEE, CREDIT_FULL, or NON_CREDITABLE.");
        }
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

        req.setStatusChangedAt(now);
        req.setUpdatedAt(now);
        ServiceRequestEntity saved = requestRepository.saveAndFlush(req);
        publishEvent("service.request.provider.matched.v1", saved, now);
        return toResponse(saved);
    }

    @Transactional
    public ServiceRequestResponse providerReject(ActorContext actor, UUID requestId, String reason) {
        ServiceRequestEntity req = requestRepository.lockById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));
        resolveProviderScope(actor, req.getAssignedProviderId());
        if (!"AWAITING_PROVIDER".equals(req.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "ServiceRequest is not in AWAITING_PROVIDER state.");
        }
        if ("WARRANTY_CLAIM".equals(req.getRequestType())) {
            return triggerWarrantyFallback(actor, requestId, reason);
        }
        Instant now = Instant.now();
        req.setFallbackReason(reason != null && !reason.isBlank() ? reason.trim() : "PROVIDER_REJECTED");
        req.setFallbackNotifiedAt(now);
        req.setStatus("AWAITING_ALTERNATIVE");
        req.setStatusChangedAt(now);
        req.setExpiryWarningNotifiedAt(null);
        req.setUpdatedAt(now);
        ServiceRequestEntity saved = requestRepository.saveAndFlush(req);
        publishEvent("service.request.fallback.available.v1", saved, now);
        return toResponse(saved);
    }

    @Transactional
    public ServiceRequestResponse triggerWarrantyFallback(ActorContext actor, UUID requestId, String reason) {
        ServiceRequestEntity req = requestRepository.lockById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));
        resolveProviderScope(actor, req.getAssignedProviderId());
        if (!"AWAITING_PROVIDER".equals(req.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "ServiceRequest is not in AWAITING_PROVIDER state.");
        }
        Instant now = Instant.now();
        req.setFallbackReason(reason != null && !reason.isBlank() ? reason.trim() : "PROVIDER_REJECTED");
        req.setFallbackNotifiedAt(now);
        req.setStatus("AWAITING_WARRANTY_FALLBACK_CONSENT");
        req.setStatusChangedAt(now);
        req.setExpiryWarningNotifiedAt(null);
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
        if (!Set.of(
                "AWAITING_ALTERNATIVE",
                "QUOTATION_REJECTED",
                "AWAITING_WARRANTY_FALLBACK_CONSENT",
                "AWAITING_PAID_REPAIR_CONSENT").contains(req.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "ServiceRequest is not awaiting fallback or alternative provider selection.");
        }
        if (req.getFallbackCount() >= MAX_FALLBACK_ROUNDS) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Maximum fallback/re-quote limit (" + MAX_FALLBACK_ROUNDS + ") reached for this ServiceRequest (D-24).");
        }
        if (input.providerId() == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "providerId is required.");
        }
        if (providerGrpcClientProvider != null) {
            ProviderGrpcClientAdapter grpc = providerGrpcClientProvider.getIfAvailable();
            if (grpc != null && grpc.isUserMemberOfProvider(input.providerId(), requesterProfileId)) {
                throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN,
                        "Active organization members cannot select their own organization (D-29).");
            }
        }
        long fee = input.inspectionFee() != null ? input.inspectionFee() : req.getInspectionFee();
        if (fee < 0) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "inspectionFee must be >= 0.");
        }
        InspectionFeePolicy feePolicy = input.inspectionFeePolicy() != null && !input.inspectionFeePolicy().isBlank()
                ? resolveFeePolicy(input.inspectionFeePolicy(), fee)
                : resolveFeePolicy(req.getInspectionFeePolicy(), fee);
        if ("AWAITING_PAID_REPAIR_CONSENT".equals(req.getStatus())) {
            req.setRequestType("PAID_REPAIR");
        }
        Instant now = Instant.now();
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
        req.setInspectionFeePolicy(feePolicy.name());
        req.setInspectionFeeWarningFlag(fee > inspectionFeeWarningThresholdVnd);
        req.setFallbackCount(req.getFallbackCount() + 1);
        req.setStatus("AWAITING_PROVIDER");
        req.setStatusChangedAt(now);
        req.setExpiryWarningNotifiedAt(null);
        req.setUpdatedAt(now);
        return toResponse(requestRepository.saveAndFlush(req));
    }

    @Transactional
    public ServiceRequestResponse adjustScope(
            String requesterProfileId,
            UUID requestId,
            AdjustScopeInput input) {
        ServiceRequestEntity req = requestRepository.lockById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));
        if (!req.getRequesterProfileId().equals(requesterProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        if (!"QUOTATION_REJECTED".equals(req.getStatus()) && !"AWAITING_ALTERNATIVE".equals(req.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Scope can only be adjusted when ServiceRequest is QUOTATION_REJECTED or AWAITING_ALTERNATIVE.");
        }
        if (req.getFallbackCount() >= MAX_FALLBACK_ROUNDS) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Maximum fallback/re-quote limit (" + MAX_FALLBACK_ROUNDS + ") reached for this ServiceRequest (D-24).");
        }
        if (input != null) {
            if (input.issueDescription() != null && !input.issueDescription().isBlank()) {
                req.setIssueDescription(input.issueDescription().trim());
            }
            if (input.preferredStart() != null) {
                req.setPreferredStart(input.preferredStart());
            }
            if (input.preferredEnd() != null) {
                if (!input.preferredEnd().isAfter(req.getPreferredStart())) {
                    throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "preferredEnd must be after preferredStart.");
                }
                req.setPreferredEnd(input.preferredEnd());
            }
            if (input.providerId() != null) {
                if (providerGrpcClientProvider != null) {
                    ProviderGrpcClientAdapter grpc = providerGrpcClientProvider.getIfAvailable();
                    if (grpc != null && grpc.isUserMemberOfProvider(input.providerId(), requesterProfileId)) {
                        throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN,
                                "Active organization members cannot select their own organization (D-29).");
                    }
                }
                req.setAssignedProviderId(input.providerId());
            }
        }
        Instant now = Instant.now();
        req.setRejectedProviderIds(null);
        req.setFallbackCount(req.getFallbackCount() + 1);
        req.setStatus("AWAITING_PROVIDER");
        req.setStatusChangedAt(now);
        req.setExpiryWarningNotifiedAt(null);
        req.setUpdatedAt(now);
        return toResponse(requestRepository.saveAndFlush(req));
    }

    @Transactional
    public ServiceRequestResponse cancelServiceRequest(
            String requesterProfileId,
            UUID requestId,
            String reason) {
        ServiceRequestEntity req = requestRepository.lockById(requestId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "ServiceRequest not found."));
        if (!req.getRequesterProfileId().equals(requesterProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        if (Set.of("BOOKED", "CANCELLED", "EXPIRED").contains(req.getStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "ServiceRequest in status " + req.getStatus() + " cannot be cancelled.");
        }
        Instant now = Instant.now();
        for (QuotationEntity q : quotationRepository.findByServiceRequestIdAndQuoteKindOrderByVersionDesc(requestId, "INITIAL")) {
            if ("SENT".equals(q.getStatus())) {
                q.setStatus("EXPIRED");
                q.setUpdatedAt(now);
                quotationRepository.save(q);
            }
        }
        req.setStatus("CANCELLED");
        req.setCancelReason(reason != null && !reason.isBlank() ? reason.trim() : "Customer cancelled");
        req.setStatusChangedAt(now);
        req.setUpdatedAt(now);
        return toResponse(requestRepository.saveAndFlush(req));
    }

    @Transactional
    public void reopenForFallbackAfterNoShow(UUID serviceRequestId, String reason, Instant now) {
        if (serviceRequestId == null) {
            return;
        }
        requestRepository.lockById(serviceRequestId).ifPresent(req -> {
            req.setStatus("AWAITING_ALTERNATIVE");
            req.setFallbackReason(reason != null && !reason.isBlank() ? reason.trim() : "TECHNICIAN_NO_SHOW");
            req.setFallbackNotifiedAt(now);
            req.setStatusChangedAt(now);
            req.setExpiryWarningNotifiedAt(null);
            req.setUpdatedAt(now);
            requestRepository.saveAndFlush(req);
            String eventType = "WARRANTY_CLAIM".equals(req.getRequestType())
                    ? "warranty.request.fallback.available.v1"
                    : "service.request.fallback.available.v1";
            publishEvent(eventType, req, now);
        });
    }

    public static Set<String> parseRejectedProviders(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
                .inspectionFeePolicy(req.getInspectionFeePolicy() != null ? req.getInspectionFeePolicy() : InspectionFeePolicy.NO_FEE.name())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private UUID resolveProviderScope(ActorContext actor, UUID assignedProviderId) {
        if (actor.isGlobalAdmin() && assignedProviderId != null) {
            return assignedProviderId;
        }
        if (actor.mode() != AccessMode.ORGANIZATION || actor.organizationId() == null) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Organization context is required for provider actions.");
        }
        if (assignedProviderId != null && !assignedProviderId.equals(actor.organizationId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Not the assigned provider for this ServiceRequest.");
        }
        return actor.organizationId();
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
                r.getInspectionFeePolicy(),
                r.isInspectionFeeWarningFlag(),
                r.getFallbackCount(),
                r.getCancelReason(),
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
