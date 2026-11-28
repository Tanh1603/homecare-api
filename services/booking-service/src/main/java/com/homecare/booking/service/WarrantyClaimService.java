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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.dto.WarrantyClaimDtos.ActivationSnapshotResponse;
import com.homecare.booking.dto.WarrantyClaimDtos.AdminVerifyWarrantyClaimRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.AppendClaimEvidenceRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.BookingWarrantySnapshotsResponse;
import com.homecare.booking.dto.WarrantyClaimDtos.CommitmentSnapshotResponse;
import com.homecare.booking.dto.WarrantyClaimDtos.RespondWarrantyClaimRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.SubmitWarrantyClaimRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.WarrantyClaimResponse;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.entity.BookingPurpose;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.entity.WarrantyActivationSnapshotEntity;
import com.homecare.booking.entity.WarrantyClaimEntity;
import com.homecare.booking.entity.WarrantyCommitmentSnapshotEntity;
import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.WarrantyClaimRepository;
import com.homecare.booking.security.BookingAccessPolicy;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Independent 3-type WarrantyClaim lifecycle service (D-05, D-12, D-14, D-18, D-28, D-30).
 */
@Service
@RequiredArgsConstructor
public class WarrantyClaimService {

    public static final Set<String> VALID_CATEGORIES = Set.of("PRODUCT_MANUFACTURER", "WORKMANSHIP", "PARTS");
    public static final Set<String> VALID_ISSUERS = Set.of("MANUFACTURER", "SELLER", "PROVIDER", "SUPPLIER");
    public static final Set<String> VALID_VERIFICATION_TARGETS = Set.of("VERIFIED", "REJECTED");
    public static final long WORKMANSHIP_SLA_HOURS = 48L;

    private final WarrantyClaimRepository claimRepository;
    private final BookingRepository bookingRepository;
    private final BookingOutboxEventRepository outboxRepository;
    private final WarrantySnapshotService snapshotService;
    private final HouseholdVerificationClient householdClient;
    private final BookingAccessPolicy accessPolicy;
    private final BookingLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public BookingWarrantySnapshotsResponse getBookingWarrantySnapshots(ActorContext actor, UUID bookingId) {
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        if (!accessPolicy.isVisible(actor, booking)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        List<CommitmentSnapshotResponse> commitments = snapshotService.listCommitments(bookingId).stream()
                .map(this::toCommitmentResponse)
                .toList();
        ActivationSnapshotResponse activation = snapshotService.findActivation(bookingId)
                .map(this::toActivationResponse)
                .orElse(null);
        return new BookingWarrantySnapshotsResponse(bookingId, commitments, activation);
    }

    @Transactional
    public WarrantyClaimResponse submitClaim(ActorContext actor, SubmitWarrantyClaimRequest request) {
        String category = normalizeEnum(request.warrantyCategory(), VALID_CATEGORIES, "warrantyCategory");
        String issuer = normalizeEnum(request.warrantyIssuer(), VALID_ISSUERS, "warrantyIssuer");
        // BR-WARR-05: Server-generated UTC millisecond reportedAt; never trust client timestamp
        Instant reportedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        UUID bookingId = request.bookingId();
        UUID deviceId = request.deviceId();
        UUID householdWarrantyId = request.householdWarrantyId();
        UUID providerId = request.providerId();
        String claimStatus = "SUBMITTED";
        String validityStatus = "ACTIVE";
        Instant providerDeadline = null;

        if ("WORKMANSHIP".equals(category)) {
            if (bookingId == null) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "bookingId is required for WORKMANSHIP claims.");
            }
            BookingEntity booking = bookingRepository.lockById(bookingId)
                    .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
            if (!booking.getRequesterProfileId().equals(actor.userId())) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Only the booking customer can submit a claim.");
            }
            if (!BookingStatus.ACCEPTED.name().equals(booking.getStatus())) {
                throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                        "WORKMANSHIP warranty claim requires an ACCEPTED booking (D-11.5).");
            }
            if (claimRepository.findOpenWorkmanshipByBookingId(bookingId).isPresent()) {
                throw new DomainException(CommonErrorCode.CONFLICT,
                        "An open WORKMANSHIP warranty claim already exists for this booking.");
            }
            WarrantyActivationSnapshotEntity activation = snapshotService.findActivation(bookingId)
                    .orElseThrow(() -> new DomainException(CommonErrorCode.CONFLICT,
                            "Booking has no Warranty Activation Snapshot."));
            if (!WarrantySnapshotService.isWorkmanshipTemporallyEligible(
                    activation.getWorkmanshipDurationDays(), activation.getEndsAt(), reportedAt)) {
                throw new DomainException(CommonErrorCode.CONFLICT,
                        "WORKMANSHIP warranty is ineligible (0-day policy or reportedAt > endsAt). "
                                + "You may submit a separate paid ServiceRequest or open a Dispute (D-05, D-12).");
            }
            deviceId = booking.getDeviceId();
            providerId = booking.getProviderId();
            providerDeadline = reportedAt.plus(WORKMANSHIP_SLA_HOURS, ChronoUnit.HOURS);
        } else if ("PRODUCT_MANUFACTURER".equals(category)) {
            if (householdWarrantyId == null && deviceId == null) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                        "householdWarrantyId or deviceId is required for PRODUCT_MANUFACTURER claims.");
            }
            if (householdWarrantyId != null) {
                var check = householdClient.checkWarrantyStatus(householdWarrantyId, actor.userId());
                if (check.expired()) {
                    validityStatus = "EXPIRED";
                }
                if (deviceId == null) {
                    deviceId = check.deviceId();
                }
                if (providerId == null) {
                    providerId = check.organizationId();
                }
            }
            // D-05 / D-14: Claims requiring manufacturer verification route to PENDING_VERIFICATION
            claimStatus = "PENDING_VERIFICATION";
        } else {
            // PARTS warranty claim
            if (bookingId == null) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "bookingId is required for PARTS claims.");
            }
            BookingEntity booking = bookingRepository.findById(bookingId)
                    .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
            if (!booking.getRequesterProfileId().equals(actor.userId())) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Only the booking customer can submit a claim.");
            }
            if (!BookingStatus.ACCEPTED.name().equals(booking.getStatus())) {
                throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                        "PARTS warranty claims can only be submitted for ACCEPTED bookings.");
            }
            deviceId = booking.getDeviceId();
            providerId = booking.getProviderId();
            if ("MANUFACTURER".equals(issuer)) {
                claimStatus = "PENDING_VERIFICATION";
            }
        }

        List<String> evidenceRefs = request.warrantyEvidenceRefs() != null
                ? request.warrantyEvidenceRefs().stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList()
                : List.of();

        WarrantyClaimEntity saved = claimRepository.saveAndFlush(WarrantyClaimEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .deviceId(deviceId)
                .householdWarrantyId(householdWarrantyId)
                .providerId(providerId)
                .requesterProfileId(actor.userId())
                .warrantyCategory(category)
                .warrantyIssuer(issuer)
                .referenceSerial(trimOrNull(request.referenceSerial()))
                .issueDescription(request.issueDescription().trim())
                .warrantyEvidenceRefs(writeJson(evidenceRefs))
                .authorizationEvidenceRefs("[]")
                .claimStatus(claimStatus)
                .warrantyVerificationStatus("UNVERIFIED")
                .authorizationVerificationStatus("UNVERIFIED")
                .validityStatus(validityStatus)
                .reportedAt(reportedAt)
                .providerResponseDeadlineAt(providerDeadline)
                .providerNoResponse(false)
                .createdAt(reportedAt)
                .updatedAt(reportedAt)
                .build());

        publishClaimEvent(DomainEventContracts.WARRANTY_CLAIM_SUBMITTED, saved, reportedAt);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyClaimResponse> listClaims(ActorContext actor, int page, int size) {
        return listClaims(actor, null, null, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyClaimResponse> listClaims(
            ActorContext actor,
            String claimStatus,
            String warrantyCategory,
            Boolean providerNoResponse,
            int page,
            int size) {
        PageResponse.validate(page, size);
        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        String statusFilter = trimUpperOrNull(claimStatus);
        String catFilter = trimUpperOrNull(warrantyCategory);
        Page<WarrantyClaimEntity> result;
        if (actor.isGlobalAdmin() && actor.mode() == AccessMode.PERSONAL) {
            result = claimRepository.findFiltered(null, null, statusFilter, catFilter, providerNoResponse, pageable);
        } else if (actor.mode() == AccessMode.ORGANIZATION && actor.organizationId() != null) {
            result = claimRepository.findFiltered(null, actor.organizationId(), statusFilter, catFilter, providerNoResponse, pageable);
        } else {
            result = claimRepository.findFiltered(actor.userId(), null, statusFilter, catFilter, providerNoResponse, pageable);
        }
        return PageResponse.of(result.map(this::toResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyClaimResponse> adminListClaims(ActorContext actor, int page, int size) {
        return adminListClaims(actor, null, null, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyClaimResponse> adminListClaims(
            ActorContext actor,
            String claimStatus,
            String warrantyCategory,
            Boolean providerNoResponse,
            int page,
            int size) {
        assertGlobalAdmin(actor);
        PageResponse.validate(page, size);
        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<WarrantyClaimEntity> result = claimRepository.findFiltered(
                null,
                null,
                trimUpperOrNull(claimStatus),
                trimUpperOrNull(warrantyCategory),
                providerNoResponse,
                pageable);
        return PageResponse.of(result.map(this::toResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public WarrantyClaimResponse getClaim(ActorContext actor, UUID claimId) {
        WarrantyClaimEntity claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Warranty claim not found."));
        assertCanViewClaim(actor, claim);
        return toResponse(claim);
    }

    @Transactional
    public WarrantyClaimResponse appendEvidence(ActorContext actor, UUID claimId, AppendClaimEvidenceRequest request) {
        WarrantyClaimEntity claim = claimRepository.lockById(claimId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Warranty claim not found."));
        assertCanViewClaim(actor, claim);
        if ("CLOSED".equals(claim.getClaimStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Cannot append evidence to a CLOSED claim.");
        }
        String kind = request.evidenceKind().trim().toUpperCase();
        String ref = request.evidenceRef().trim();
        if ("AUTHORIZATION".equals(kind)) {
            if (!actor.isGlobalAdmin() && actor.mode() != AccessMode.ORGANIZATION) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                        "Only Provider or Admin can append AUTHORIZATION evidence.");
            }
            List<String> list = new ArrayList<>(readJsonList(claim.getAuthorizationEvidenceRefs()));
            list.add(ref);
            claim.setAuthorizationEvidenceRefs(writeJson(list));
        } else {
            List<String> list = new ArrayList<>(readJsonList(claim.getWarrantyEvidenceRefs()));
            list.add(ref);
            claim.setWarrantyEvidenceRefs(writeJson(list));
        }
        // D-14: Appending evidence NEVER changes warrantyVerificationStatus or authorizationVerificationStatus
        claim.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return toResponse(claimRepository.saveAndFlush(claim));
    }

    @Transactional
    public WarrantyClaimResponse respondToClaim(
            ActorContext actor,
            UUID claimId,
            String ifMatch,
            RespondWarrantyClaimRequest request) {
        WarrantyClaimEntity claim = claimRepository.lockById(claimId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Warranty claim not found."));
        if (!isAssignedOrgManagerOrAdmin(actor, claim)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Only the responsible Provider (ORG_OWNER/ORG_ADMIN) or Admin can respond to this warranty claim.");
        }
        EtagSupport.verifyIfMatch(ifMatch, claim.getVersion());
        if (!Set.of("SUBMITTED", "PENDING_VERIFICATION").contains(claim.getClaimStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Only SUBMITTED or PENDING_VERIFICATION claims can be responded to.");
        }
        if ("PRODUCT_MANUFACTURER".equals(claim.getWarrantyCategory())
                && Boolean.TRUE.equals(request.accepted())
                && !"VERIFIED".equals(claim.getAuthorizationVerificationStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "PRODUCT_MANUFACTURER claim requires Admin-verified provider authorization before acceptance (D-05, D-14).");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (Boolean.TRUE.equals(request.accepted())) {
            claim.setClaimStatus("ACCEPTED");
            claim.setRejectionReason(null);
            // D-30 CAND-WARR-02: Atomically spawn 0 VND WARRANTY_REWORK Booking for WORKMANSHIP claims
            if ("WORKMANSHIP".equals(claim.getWarrantyCategory()) && claim.getReworkBookingId() == null) {
                BookingEntity reworkBooking = createZeroVndReworkBooking(claim, request.preferredStart(), request.preferredEnd(), now);
                claim.setReworkBookingId(reworkBooking.getId());
            }
        } else {
            if (request.rejectionReason() == null || request.rejectionReason().isBlank()) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                        "rejectionReason is required when rejecting a warranty claim.");
            }
            // D-05: Rejected claim NEVER auto-creates a paid Booking
            claim.setClaimStatus("REJECTED");
            claim.setRejectionReason(request.rejectionReason().trim());
        }
        claim.setUpdatedAt(now);
        WarrantyClaimEntity saved = claimRepository.saveAndFlush(claim);
        publishClaimEvent(DomainEventContracts.WARRANTY_CLAIM_RESPONDED, saved, now);
        return toResponse(saved);
    }

    @Transactional
    public WarrantyClaimResponse closeClaim(ActorContext actor, UUID claimId, String ifMatch) {
        WarrantyClaimEntity claim = claimRepository.lockById(claimId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Warranty claim not found."));
        if (!actor.isGlobalAdmin() && !claim.getRequesterProfileId().equals(actor.userId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Only the customer or Admin can close this claim.");
        }
        EtagSupport.verifyIfMatch(ifMatch, claim.getVersion());
        if (!Set.of("ACCEPTED", "REJECTED").contains(claim.getClaimStatus())) {
            throw new DomainException(CommonErrorCode.CONFLICT, "Only ACCEPTED or REJECTED claims can be closed.");
        }
        claim.setClaimStatus("CLOSED");
        claim.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return toResponse(claimRepository.saveAndFlush(claim));
    }

    @Transactional
    public WarrantyClaimResponse adminVerifyClaim(
            ActorContext actor,
            UUID claimId,
            String ifMatch,
            AdminVerifyWarrantyClaimRequest request) {
        // D-14: Strictly Global ADMIN only; Providers/Customers ALWAYS receive 403
        assertGlobalAdmin(actor);
        WarrantyClaimEntity claim = claimRepository.lockById(claimId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND, "Warranty claim not found."));
        EtagSupport.verifyIfMatch(ifMatch, claim.getVersion());

        boolean changed = false;
        boolean anyRejected = false;
        if (request.warrantyVerificationStatus() != null && !request.warrantyVerificationStatus().isBlank()) {
            String target = normalizeEnum(request.warrantyVerificationStatus(), VALID_VERIFICATION_TARGETS, "warrantyVerificationStatus");
            claim.setWarrantyVerificationStatus(target);
            changed = true;
            if ("REJECTED".equals(target)) {
                anyRejected = true;
            }
        }
        if (request.authorizationVerificationStatus() != null && !request.authorizationVerificationStatus().isBlank()) {
            String target = normalizeEnum(request.authorizationVerificationStatus(), VALID_VERIFICATION_TARGETS, "authorizationVerificationStatus");
            claim.setAuthorizationVerificationStatus(target);
            changed = true;
            if ("REJECTED".equals(target)) {
                anyRejected = true;
            }
        }
        if (!changed) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "At least one of warrantyVerificationStatus or authorizationVerificationStatus must be provided.");
        }
        if (anyRejected && (request.note() == null || request.note().isBlank())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Verification note is mandatory when rejecting warranty or authorization evidence.");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        claim.setVerifiedBy(actor.userId());
        claim.setVerifiedAt(now);
        if (request.note() != null && !request.note().isBlank()) {
            claim.setRejectionReason(request.note().trim());
        }
        // D-05 item 6: VERIFIED NEVER automatically transitions claimStatus to ACCEPTED
        claim.setUpdatedAt(now);
        WarrantyClaimEntity saved = claimRepository.saveAndFlush(claim);
        publishClaimEvent(DomainEventContracts.WARRANTY_CLAIM_VERIFIED, saved, now);
        return toResponse(saved);
    }

    @Transactional
    public int escalateOverdueWorkmanshipClaims(Instant now, int batchSize) {
        List<WarrantyClaimEntity> overdue = claimRepository.lockOverdueWorkmanshipClaims(now, batchSize);
        for (WarrantyClaimEntity claim : overdue) {
            claim.setProviderNoResponse(true);
            claim.setUpdatedAt(now);
            claimRepository.saveAndFlush(claim);
            publishClaimEvent(DomainEventContracts.WARRANTY_CLAIM_SLA_ESCALATED, claim, now);
        }
        return overdue.size();
    }

    private BookingEntity createZeroVndReworkBooking(
            WarrantyClaimEntity claim,
            Instant preferredStart,
            Instant preferredEnd,
            Instant now) {
        BookingEntity original = bookingRepository.findById(claim.getBookingId())
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        Instant start = preferredStart != null ? preferredStart : now.plus(Duration.ofHours(24));
        Instant end = preferredEnd != null && preferredEnd.isAfter(start)
                ? preferredEnd
                : start.plus(Duration.ofHours(2));

        BookingEntity rework = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(original.getRequesterProfileId())
                .requesterName(original.getRequesterName())
                .serviceId(original.getServiceId())
                .serviceName("[Bảo hành tay nghề] " + original.getServiceName())
                .providerId(original.getProviderId())
                .preferredProviderId(original.getProviderId())
                .bookingType("WARRANTY_REWORK")
                .purpose(BookingPurpose.WARRANTY_REWORK.name())
                .deviceId(original.getDeviceId())
                .deviceType(original.getDeviceType())
                .preferredStart(start)
                .preferredEnd(end)
                .address(original.getAddress())
                .serviceAddressId(original.getServiceAddressId())
                .recipientName(original.getRecipientName())
                .phoneNumber(original.getPhoneNumber())
                .addressLine(original.getAddressLine())
                .wardCode(original.getWardCode())
                .wardName(original.getWardName())
                .provinceCode(original.getProvinceCode())
                .provinceName(original.getProvinceName())
                .formattedAddress(original.getFormattedAddress())
                .latitude(original.getLatitude())
                .longitude(original.getLongitude())
                .notes("0 VND Warranty Rework for Claim " + claim.getId() + ": " + claim.getIssueDescription())
                .status(BookingStatus.REQUESTED.name())
                .allocationStatus("UNALLOCATED")
                .quotedPrice(0L)
                .reworkSourceClaimId(claim.getId())
                .warrantyRework(true)
                .createdAt(now)
                .updatedAt(now)
                .build());
        lifecycleService.recordHistory(rework.getId(), null, rework.getStatus(), claim.getRequesterProfileId(),
                "Created 0 VND WARRANTY_REWORK booking from accepted claim " + claim.getId() + " (D-30)");
        return rework;
    }

    private void assertGlobalAdmin(ActorContext actor) {
        if (actor == null || !actor.isGlobalAdmin()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Only Global Admin can verify manufacturer warranty or authorization (D-14).");
        }
    }

    private boolean isAssignedOrgManagerOrAdmin(ActorContext actor, WarrantyClaimEntity claim) {
        if (actor.isGlobalAdmin()) {
            return true;
        }
        return actor.mode() == AccessMode.ORGANIZATION
                && actor.organizationId() != null
                && actor.organizationId().equals(claim.getProviderId())
                && actor.hasOrgRole("ORG_OWNER", "ORG_ADMIN");
    }

    private void assertCanViewClaim(ActorContext actor, WarrantyClaimEntity claim) {
        if (actor.isGlobalAdmin()) {
            return;
        }
        if (actor.userId().equals(claim.getRequesterProfileId())) {
            return;
        }
        if (actor.mode() == AccessMode.ORGANIZATION
                && actor.organizationId() != null
                && actor.organizationId().equals(claim.getProviderId())) {
            return;
        }
        throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    private String normalizeEnum(String raw, Set<String> allowed, String fieldName) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Invalid " + fieldName + ": '" + raw + "'. Allowed: " + allowed);
        }
        return normalized;
    }

    private String trimOrNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private String trimUpperOrNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim().toUpperCase();
    }

    private void publishClaimEvent(String eventType, WarrantyClaimEntity claim, Instant now) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("claimId", claim.getId().toString());
            data.put("bookingId", claim.getBookingId() != null ? claim.getBookingId().toString() : "");
            data.put("providerId", claim.getProviderId() != null ? claim.getProviderId().toString() : "");
            data.put("requesterProfileId", claim.getRequesterProfileId());
            data.put("warrantyCategory", claim.getWarrantyCategory());
            data.put("claimStatus", claim.getClaimStatus());
            data.put("providerNoResponse", claim.isProviderNoResponse());
            if (claim.getReworkBookingId() != null) {
                data.put("reworkBookingId", claim.getReworkBookingId().toString());
            }

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString());
            envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, eventType);
            envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
            envelope.put(DomainEventContracts.FIELD_PRODUCER, "booking-service");
            envelope.put(DomainEventContracts.FIELD_DATA, data);

            outboxRepository.save(BookingOutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("WARRANTY_CLAIM")
                    .aggregateId(claim.getId())
                    .eventType(eventType)
                    .routingKey(eventType)
                    .payloadJson(objectMapper.writeValueAsString(envelope))
                    .status(EntityStatus.PENDING.name())
                    .retryCount(0)
                    .createdAt(now)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist warranty claim outbox event " + eventType, ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize JSON", ex);
        }
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private CommitmentSnapshotResponse toCommitmentResponse(WarrantyCommitmentSnapshotEntity e) {
        return new CommitmentSnapshotResponse(
                e.getId(), e.getBookingId(), e.getQuotationId(), e.getQuotationVersion(),
                e.getServiceCategory(), e.getWorkmanshipDurationDays(), e.getCommittedPartsJson(),
                e.getPolicySummary(), e.getCommittedAt(), e.getCommittedByProfileId());
    }

    private ActivationSnapshotResponse toActivationResponse(WarrantyActivationSnapshotEntity e) {
        return new ActivationSnapshotResponse(
                e.getId(), e.getBookingId(), e.getCommitmentSnapshotId(), e.getWorkmanshipDurationDays(),
                e.getStartsAt(), e.getEndsAt(), e.getActualPartsJson(), e.getExcludedScopeJson(),
                e.isMaterialDiscrepancy(), e.getDiscrepancySummary(), e.getCreatedAt());
    }

    private WarrantyClaimResponse toResponse(WarrantyClaimEntity e) {
        return new WarrantyClaimResponse(
                e.getId(), e.getBookingId(), e.getDeviceId(), e.getHouseholdWarrantyId(), e.getProviderId(),
                e.getRequesterProfileId(), e.getWarrantyCategory(), e.getWarrantyIssuer(), e.getReferenceSerial(),
                e.getIssueDescription(), readJsonList(e.getWarrantyEvidenceRefs()),
                readJsonList(e.getAuthorizationEvidenceRefs()), e.getClaimStatus(),
                e.getWarrantyVerificationStatus(), e.getAuthorizationVerificationStatus(), e.getValidityStatus(),
                e.getReportedAt(), e.getProviderResponseDeadlineAt(), e.isProviderNoResponse(),
                e.getReworkBookingId(), e.getVerifiedBy(), e.getVerifiedAt(), e.getRejectionReason(),
                e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
