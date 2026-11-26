package com.homecare.booking.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.common.event.DomainEventContracts;

import lombok.RequiredArgsConstructor;

/**
 * FOR UPDATE SKIP LOCKED timer worker for Feature 4 (D-11, D-26, D-27):
 * 1) Day 3 reminder for COMPLETED_PENDING_ACCEPTANCE
 * 2) Day 7 SYSTEM_TIMEOUT auto-accept (strictly when completionNotifiedAt != null && !notificationFailed)
 * 3) 48h REVISION_REQUESTED timeout -> DISPUTED + auto-open Dispute
 * 4) 14d WAITING_FOR_PARTS reminders at Day 7, Day 12, Day 14 Admin flag
 */
@Component
@RequiredArgsConstructor
public class BookingAcceptanceTimerWorker {

    private static final int BATCH_SIZE = 50;

    private final BookingRepository bookingRepository;
    private final BookingAcceptanceService acceptanceService;
    private final BookingLifecycleService lifecycleService;
    private final DisputeService disputeService;

    public record TimerRunSummary(
            int acceptanceReminders,
            int autoAccepted,
            int revisionTimeouts,
            int waitingForPartsUpdates) {
    }

    @Scheduled(fixedDelayString = "${homecare.booking.acceptance-timer-poll-ms:60000}")
    public void scheduledRun() {
        processTimers(Instant.now());
    }

    @Transactional
    public TimerRunSummary processTimers(Instant now) {
        int reminders = sendDay3Reminders(now);
        int autoAccepted = processAutoAccepts(now);
        int revisionTimeouts = processRevisionTimeouts(now);
        int waitingUpdates = processWaitingForParts(now);
        return new TimerRunSummary(reminders, autoAccepted, revisionTimeouts, waitingUpdates);
    }

    private int sendDay3Reminders(Instant now) {
        Instant cutoff = now.minus(3, ChronoUnit.DAYS);
        List<BookingEntity> due = bookingRepository.lockDueForAcceptanceReminder(cutoff, BATCH_SIZE);
        for (BookingEntity booking : due) {
            booking.setAcceptanceReminderSentAt(now);
            booking.setUpdatedAt(now);
            bookingRepository.save(booking);
            lifecycleService.publishDomainEvent(DomainEventContracts.BOOKING_ACCEPTANCE_REMINDER, booking);
        }
        return due.size();
    }

    private int processAutoAccepts(Instant now) {
        List<BookingEntity> due = bookingRepository.lockDueForAutoAccept(now, BATCH_SIZE);
        for (BookingEntity booking : due) {
            acceptanceService.finalizeAcceptance(
                    booking, "SYSTEM_TIMEOUT", "SYSTEM_TIMER",
                    "Auto-accepted after 7 calendar days (Asia/Ho_Chi_Minh) without revision or dispute");
        }
        return due.size();
    }

    private int processRevisionTimeouts(Instant now) {
        Instant cutoff48h = now.minus(48, ChronoUnit.HOURS);
        List<BookingEntity> timedOut = bookingRepository.lockTimedOutRevisions(cutoff48h, BATCH_SIZE);
        for (BookingEntity booking : timedOut) {
            String previous = booking.getStatus();
            booking.setStatus(BookingStatus.DISPUTED.name());
            booking.setRevisionRequestedAt(null);
            booking.setUpdatedAt(now);
            BookingEntity saved = bookingRepository.saveAndFlush(booking);
            lifecycleService.recordHistory(saved.getId(), previous, saved.getStatus(), "SYSTEM_TIMER",
                    "Provider did not respond to REVISION_REQUESTED within 48h -> DISPUTED (D-27)");
            disputeService.openSystemDisputeForBooking(saved, saved.getRequesterProfileId(), "REVISION_TIMEOUT",
                    "Provider did not respond to revision request within 48 hours.", now);
        }
        return timedOut.size();
    }

    private int processWaitingForParts(Instant now) {
        int count = 0;
        count += advanceWaitingStage(7, now.minus(7, ChronoUnit.DAYS), now, false);
        count += advanceWaitingStage(12, now.minus(12, ChronoUnit.DAYS), now, false);
        count += advanceWaitingStage(14, now.minus(14, ChronoUnit.DAYS), now, true);
        return count;
    }

    private int advanceWaitingStage(int stage, Instant cutoff, Instant now, boolean flagAdmin) {
        List<BookingEntity> due = bookingRepository.lockWaitingForPartsDue(stage, cutoff, BATCH_SIZE);
        for (BookingEntity booking : due) {
            booking.setWaitingForPartsReminderStage(stage);
            if (flagAdmin) {
                booking.setAdminReviewFlagged(true);
                booking.setAdminReviewReason("WAITING_FOR_PARTS exceeded 14-day TTL (D-26)");
            }
            booking.setUpdatedAt(now);
            bookingRepository.save(booking);
            lifecycleService.publishDomainEvent(DomainEventContracts.BOOKING_WAITING_FOR_PARTS_REMINDER, booking);
        }
        return due.size();
    }
}
