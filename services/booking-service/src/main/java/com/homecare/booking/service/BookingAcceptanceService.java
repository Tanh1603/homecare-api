package com.homecare.booking.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingExecutionDtos.AcceptBookingRequest;
import com.homecare.booking.dto.BookingExecutionDtos.AdminNotificationFailedBookingDetailResponse;
import com.homecare.booking.dto.BookingExecutionDtos.AdminNotificationFailedBookingResponse;
import com.homecare.booking.dto.BookingExecutionDtos.RefuseRevisionRequest;
import com.homecare.booking.dto.BookingExecutionDtos.RequestRevisionRequest;
import com.homecare.booking.dto.BookingExecutionDtos.StartReworkRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.exception.BookingErrorCode;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.WorkEvidenceRepository;
import com.homecare.booking.security.BookingAccessPolicy;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * Cohesive service for Conditional Acceptance & Revision Loop (D-11, D-27):
 * - Notification delivery proof sets completionNotifiedAt + Asia/Ho_Chi_Minh Day 0 + 7d autoAcceptAt
 * - Explicit CUSTOMER acceptance -> ACCEPTED (sole trigger for BOOKING_ACCEPTED)
 * - REVISION_REQUESTED (max 2; 3rd / provider refusal / 48h timeout -> DISPUTED + auto-open Dispute)
 */
@Service
@RequiredArgsConstructor
public class BookingAcceptanceService {

    public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int MAX_REVISIONS = 2;

    public static final String NOTIFICATION_FAILED_PREFIX = "NOTIFICATION_FAILED";

    private final BookingRepository bookingRepository;
    private final WorkEvidenceRepository workEvidenceRepository;
    private final BookingAccessPolicy accessPolicy;
    private final BookingLifecycleService lifecycleService;
    private final DisputeService disputeService;
    private final ObjectProvider<WarrantySnapshotService> warrantySnapshotServiceProvider;

    public static Instant computeAutoAcceptAt(Instant notifiedAt) {
        LocalDate day0 = notifiedAt.atZone(VN_ZONE).toLocalDate();
        return day0.plusDays(7)
                .atTime(23, 59, 59, 999_000_000)
                .atZone(VN_ZONE)
                .toInstant();
    }

    @Transactional
    public void recordCompletionNotificationDelivered(UUID bookingId, Instant deliveredAt) {
        bookingRepository.lockById(bookingId).ifPresent(booking -> {
            if (!BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name().equals(booking.getStatus())) {
                return;
            }
            if (booking.getCompletionNotifiedAt() != null) {
                return;
            }
            Instant ts = (deliveredAt != null ? deliveredAt : Instant.now()).truncatedTo(ChronoUnit.MILLIS);
            booking.setCompletionNotifiedAt(ts);
            clearNotificationFailureFlags(booking);
            booking.setAutoAcceptAt(computeAutoAcceptAt(ts));
            booking.setUpdatedAt(Instant.now());
            bookingRepository.saveAndFlush(booking);
        });
    }

    @Transactional
    public void recordCompletionNotificationFailed(UUID bookingId, String reason) {
        bookingRepository.lockById(bookingId).ifPresent(booking -> {
            if (!BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name().equals(booking.getStatus())) {
                return;
            }
            if (booking.getCompletionNotifiedAt() != null) {
                // D-27: Ignore stale failed.v1 if delivery confirmation has already been recorded.
                return;
            }
            markNotificationFailed(
                    booking,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    reason != null && !reason.isBlank() ? reason : "Broker delivery retries exhausted");
            bookingRepository.saveAndFlush(booking);
        });
    }

    public void flagOverdueCompletionNotification(BookingEntity booking, Instant now) {
        if (!BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name().equals(booking.getStatus())
                || booking.getCompletionNotifiedAt() != null
                || booking.isNotificationFailed()) {
            return;
        }
        markNotificationFailed(
                booking,
                now.truncatedTo(ChronoUnit.MILLIS),
                "Completion notification unconfirmed after 24h (D-27)");
        bookingRepository.saveAndFlush(booking);
        lifecycleService.recordHistory(
                booking.getId(),
                booking.getStatus(),
                booking.getStatus(),
                "SYSTEM_TIMER",
                "NOTIFICATION_FAILED: Unconfirmed completion notification after 24h");
    }

    private void markNotificationFailed(BookingEntity booking, Instant now, String reason) {
        booking.setNotificationFailed(true);
        if (booking.getNotificationFailedAt() == null) {
            booking.setNotificationFailedAt(now);
        }
        booking.setAutoAcceptAt(null);
        booking.setAdminReviewFlagged(true);
        booking.setAdminReviewReason(NOTIFICATION_FAILED_PREFIX + ": " + reason);
        booking.setUpdatedAt(now);
    }

    private void clearNotificationFailureFlags(BookingEntity booking) {
        booking.setNotificationFailed(false);
        booking.setNotificationFailedAt(null);
        if (booking.getAdminReviewReason() != null
                && booking.getAdminReviewReason().startsWith(NOTIFICATION_FAILED_PREFIX)) {
            booking.setAdminReviewFlagged(false);
            booking.setAdminReviewReason(null);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminNotificationFailedBookingResponse> listNotificationFailedBookings(int page, int size) {
        PageResponse.validate(page, size);
        Page<BookingEntity> result = bookingRepository.findByStatusAndNotificationFailedTrueOrderByNotificationFailedAtDesc(
                BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name(),
                PageRequest.of(page - 1, size));
        return PageResponse.of(
                result.map(this::toAdminFailedResponse).getContent(),
                page,
                size,
                result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AdminNotificationFailedBookingDetailResponse getNotificationFailedBooking(UUID bookingId) {
        BookingEntity booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        int evidenceCount = workEvidenceRepository.findByBookingId(bookingId).size();
        return toAdminFailedDetailResponse(booking, evidenceCount);
    }

    @Transactional
    public AdminNotificationFailedBookingDetailResponse retryCompletionNotification(
            ActorContext actor,
            UUID bookingId,
            String ifMatch,
            String note) {
        if (!actor.isGlobalAdmin()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Admin role required.");
        }
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name().equals(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Booking must be in COMPLETED_PENDING_ACCEPTANCE to retry completion notification.");
        }
        if (booking.getCompletionNotifiedAt() != null) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Completion notification delivery has already been confirmed.");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        booking.setNotificationRetryCount(booking.getNotificationRetryCount() + 1);
        booking.setLastNotificationRetryAt(now);
        booking.setUpdatedAt(now);
        BookingEntity saved = bookingRepository.saveAndFlush(booking);
        String historyNote = note != null && !note.isBlank()
                ? "Admin retried completion notification (#" + saved.getNotificationRetryCount() + "): " + note.trim()
                : "Admin retried completion notification (#" + saved.getNotificationRetryCount() + ")";
        lifecycleService.recordHistory(saved.getId(), saved.getStatus(), saved.getStatus(), actor.userId(), historyNote);
        lifecycleService.publishDomainEvent(DomainEventContracts.BOOKING_COMPLETION_PENDING_ACCEPTANCE, saved);
        int evidenceCount = workEvidenceRepository.findByBookingId(bookingId).size();
        return toAdminFailedDetailResponse(saved, evidenceCount);
    }

    private AdminNotificationFailedBookingResponse toAdminFailedResponse(BookingEntity b) {
        return new AdminNotificationFailedBookingResponse(
                b.getId(),
                b.getProviderId(),
                b.getRequesterProfileId(),
                b.getServiceName(),
                b.getCompletionSubmittedAt(),
                b.getNotificationFailedAt(),
                b.getNotificationRetryCount(),
                b.getLastNotificationRetryAt(),
                b.getAdminReviewReason(),
                b.getVersion());
    }

    private AdminNotificationFailedBookingDetailResponse toAdminFailedDetailResponse(
            BookingEntity b, int evidenceCount) {
        return new AdminNotificationFailedBookingDetailResponse(
                b.getId(),
                b.getProviderId(),
                b.getRequesterProfileId(),
                b.getServiceName(),
                b.getStatus(),
                b.getCompletionSummary(),
                evidenceCount,
                b.getCompletionSubmittedAt(),
                b.getNotificationFailedAt(),
                b.getNotificationRetryCount(),
                b.getLastNotificationRetryAt(),
                b.getAdminReviewReason(),
                b.getVersion());
    }

    @Transactional
    public BookingResponse acceptBooking(
            ActorContext actor,
            UUID bookingId,
            String ifMatch,
            AcceptBookingRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        if (!actor.isGlobalAdmin() && !booking.getRequesterProfileId().equals(actor.userId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Only the customer can accept this booking.");
        }
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name().equals(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Booking must be in COMPLETED_PENDING_ACCEPTANCE to accept.");
        }
        return finalizeAcceptance(booking, "CUSTOMER", actor.userId(),
                request != null && request.note() != null && !request.note().isBlank()
                        ? request.note().trim()
                        : "Customer accepted completed work");
    }

    @Transactional
    public BookingResponse finalizeAcceptance(
            BookingEntity booking,
            String acceptanceSource,
            String actorId,
            String reason) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String previous = booking.getStatus();
        booking.setStatus(BookingStatus.ACCEPTED.name());
        booking.setAcceptedAt(now);
        booking.setAcceptanceSource(acceptanceSource);
        booking.setAutoAcceptAt(null);
        clearNotificationFailureFlags(booking);
        booking.setUpdatedAt(now);
        BookingEntity saved = bookingRepository.saveAndFlush(booking);
        WarrantySnapshotService snapshotService = warrantySnapshotServiceProvider != null
                ? warrantySnapshotServiceProvider.getIfAvailable()
                : null;
        if (!saved.isWarrantyRework() && snapshotService != null) {
            snapshotService.createActivationSnapshotOnAcceptance(saved);
        }
        lifecycleService.recordHistory(saved.getId(), previous, saved.getStatus(), actorId, reason);
        lifecycleService.publishDomainEvent(DomainEventContracts.BOOKING_ACCEPTED, saved);
        return lifecycleService.toBookingResponse(saved);
    }

    @Transactional
    public BookingResponse requestRevision(
            ActorContext actor,
            UUID bookingId,
            String ifMatch,
            RequestRevisionRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        if (!booking.getRequesterProfileId().equals(actor.userId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Only the customer can request a revision.");
        }
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name().equals(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Revision can only be requested in COMPLETED_PENDING_ACCEPTANCE.");
        }
        Instant now = Instant.now();
        String previous = booking.getStatus();
        // D-11.3: Max 2 revisions; 3rd attempt auto-escalates to DISPUTED + opens Dispute
        if (booking.getRevisionCount() >= MAX_REVISIONS) {
            booking.setStatus(BookingStatus.DISPUTED.name());
            booking.setAutoAcceptAt(null);
            booking.setUpdatedAt(now);
            BookingEntity saved = bookingRepository.saveAndFlush(booking);
            lifecycleService.recordHistory(bookingId, previous, saved.getStatus(), actor.userId(),
                    "3rd revision attempt auto-escalated to DISPUTED: " + request.reason().trim());
            disputeService.openSystemDisputeForBooking(saved, actor.userId(), "QUALITY_ISSUE",
                    "Auto-escalated after reaching max 2 revisions: " + request.reason().trim(), now);
            return lifecycleService.toBookingResponse(saved);
        }

        booking.setStatus(BookingStatus.REVISION_REQUESTED.name());
        booking.setRevisionCount(booking.getRevisionCount() + 1);
        booking.setRevisionRequestedAt(now);
        booking.setAutoAcceptAt(null);
        booking.setUpdatedAt(now);
        BookingEntity saved = bookingRepository.saveAndFlush(booking);
        lifecycleService.recordHistory(bookingId, previous, saved.getStatus(), actor.userId(),
                "REVISION_REQUESTED (#" + saved.getRevisionCount() + "): " + request.reason().trim());
        lifecycleService.publishDomainEvent(DomainEventContracts.BOOKING_REVISION_REQUESTED, saved);
        return lifecycleService.toBookingResponse(saved);
    }

    @Transactional
    public BookingResponse startRework(
            ActorContext actor,
            UUID bookingId,
            String ifMatch,
            StartReworkRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        accessPolicy.assertCanAppendWorkUpdate(actor, booking);
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!BookingStatus.REVISION_REQUESTED.name().equals(booking.getStatus())
                && !BookingStatus.DISPUTED.name().equals(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Rework can only start from REVISION_REQUESTED or DISPUTED.");
        }
        Instant now = Instant.now();
        String previous = booking.getStatus();
        booking.setStatus(BookingStatus.IN_PROGRESS.name());
        booking.setRevisionRequestedAt(null);
        booking.setUpdatedAt(now);
        BookingEntity saved = bookingRepository.saveAndFlush(booking);
        String note = request != null && request.note() != null && !request.note().isBlank()
                ? request.note().trim()
                : "Provider started rework";
        lifecycleService.recordHistory(bookingId, previous, saved.getStatus(), actor.userId(), note);
        return lifecycleService.toBookingResponse(saved);
    }

    @Transactional
    public BookingResponse refuseRevision(
            ActorContext actor,
            UUID bookingId,
            String ifMatch,
            RefuseRevisionRequest request) {
        BookingEntity booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(BookingErrorCode.BOOKING_NOT_FOUND));
        if (!actor.isGlobalAdmin()
                && (actor.mode() != AccessMode.ORGANIZATION
                        || actor.organizationId() == null
                        || !actor.organizationId().equals(booking.getProviderId()))) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Only the assigned organization can refuse a revision.");
        }
        EtagSupport.verifyIfMatch(ifMatch, booking.getVersion());
        if (!BookingStatus.REVISION_REQUESTED.name().equals(booking.getStatus())) {
            throw new DomainException(BookingErrorCode.BOOKING_INVALID_STATE,
                    "Booking must be in REVISION_REQUESTED to refuse revision.");
        }
        Instant now = Instant.now();
        String previous = booking.getStatus();
        booking.setStatus(BookingStatus.DISPUTED.name());
        booking.setRevisionRequestedAt(null);
        booking.setUpdatedAt(now);
        BookingEntity saved = bookingRepository.saveAndFlush(booking);
        lifecycleService.recordHistory(bookingId, previous, saved.getStatus(), actor.userId(),
                "Provider refused revision -> DISPUTED: " + request.reason().trim());
        disputeService.openSystemDisputeForBooking(saved, saved.getRequesterProfileId(), "QUALITY_ISSUE",
                "Provider refused revision: " + request.reason().trim(), now);
        return lifecycleService.toBookingResponse(saved);
    }
}
