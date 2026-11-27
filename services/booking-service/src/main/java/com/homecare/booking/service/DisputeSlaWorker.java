package com.homecare.booking.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.booking.entity.DisputeEntity;
import com.homecare.booking.entity.DisputeStatus;
import com.homecare.booking.repository.DisputeRepository;
import com.homecare.common.event.DomainEventContracts;

import lombok.RequiredArgsConstructor;

/**
 * FOR UPDATE SKIP LOCKED SLA worker for Disputes (D-07.4, D-27, D-30):
 * - Provider 48h SLA (24h reminder; at 48h marks providerNoResponse=true -> UNDER_REVIEW)
 * - Customer 7d SLA in AWAITING_CUSTOMER_EVIDENCE / RESOLUTION_PROPOSED
 */
@Component
@RequiredArgsConstructor
public class DisputeSlaWorker {

    private static final int BATCH_SIZE = 50;

    private final DisputeRepository disputeRepository;
    private final DisputeService disputeService;

    public record DisputeSlaSummary(int providerReminders, int providerNoResponses, int customerSlaExpired) {
    }

    @Scheduled(fixedDelayString = "${homecare.dispute.sla-poll-ms:60000}")
    public void scheduledRun() {
        processSla(Instant.now());
    }

    @Transactional
    public DisputeSlaSummary processSla(Instant now) {
        int reminders = sendProvider24hReminders(now)
                + sendCustomerSlaReminders(now, 3, 4)
                + sendCustomerSlaReminders(now, 5, 2);
        int noResponses = handleProvider48hTimeouts(now);
        int customerExpired = handleCustomer7dTimeouts(now);
        return new DisputeSlaSummary(reminders, noResponses, customerExpired);
    }

    private int sendCustomerSlaReminders(Instant now, int targetStage, int remainingDays) {
        Instant reminderCutoff = now.plus(remainingDays, ChronoUnit.DAYS);
        List<DisputeEntity> due = disputeRepository.lockCustomerSlaReminderDue(reminderCutoff, targetStage, BATCH_SIZE);
        for (DisputeEntity dispute : due) {
            dispute.setSlaReminderStage(targetStage);
            dispute.setUpdatedAt(now);
            disputeRepository.save(dispute);
            disputeService.publishDisputeEvent(
                    DomainEventContracts.DISPUTE_SLA_REMINDER, dispute, dispute.getOpenedByProfileId(), now);
        }
        return due.size();
    }

    private int sendProvider24hReminders(Instant now) {
        Instant reminderCutoff = now.plus(24, ChronoUnit.HOURS);
        List<DisputeEntity> due = disputeRepository.lockProviderSlaReminderDue(reminderCutoff, BATCH_SIZE);
        for (DisputeEntity dispute : due) {
            dispute.setSlaReminderStage(24);
            dispute.setUpdatedAt(now);
            disputeRepository.save(dispute);
            disputeService.publishDisputeEvent(
                    DomainEventContracts.DISPUTE_SLA_REMINDER, dispute, dispute.getOpenedByProfileId(), now);
        }
        return due.size();
    }

    private int handleProvider48hTimeouts(Instant now) {
        List<DisputeEntity> expired = disputeRepository.lockExpiredProviderSla(now, BATCH_SIZE);
        for (DisputeEntity dispute : expired) {
            // D-07.4: Record non-response without treating silence as legal admission
            dispute.setProviderNoResponse(true);
            dispute.setStatus(DisputeStatus.UNDER_REVIEW.name());
            dispute.setSlaDueAt(null);
            dispute.setUpdatedAt(now);
            disputeRepository.save(dispute);
            disputeService.publishDisputeEvent(
                    DomainEventContracts.DISPUTE_SLA_REMINDER, dispute, dispute.getOpenedByProfileId(), now);
        }
        return expired.size();
    }

    private int handleCustomer7dTimeouts(Instant now) {
        List<DisputeEntity> expired = disputeRepository.lockExpiredCustomerSla(now, BATCH_SIZE);
        for (DisputeEntity dispute : expired) {
            dispute.setStatus(DisputeStatus.UNDER_REVIEW.name());
            dispute.setSlaDueAt(null);
            dispute.setSlaReminderStage(7);
            dispute.setUpdatedAt(now);
            disputeRepository.save(dispute);
            disputeService.publishDisputeEvent(
                    DomainEventContracts.DISPUTE_SLA_REMINDER, dispute, dispute.getOpenedByProfileId(), now);
        }
        return expired.size();
    }
}
