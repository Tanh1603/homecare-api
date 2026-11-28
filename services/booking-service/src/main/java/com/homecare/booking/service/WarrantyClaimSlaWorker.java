package com.homecare.booking.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * FOR UPDATE SKIP LOCKED worker escalating WORKMANSHIP claims unresponded after 48h (D-28).
 */
@Component
@RequiredArgsConstructor
public class WarrantyClaimSlaWorker {

    private static final int BATCH_SIZE = 50;

    private final WarrantyClaimService warrantyClaimService;

    @Scheduled(fixedDelayString = "${homecare.booking.warranty-claim-sla-poll-ms:60000}")
    public void scheduledRun() {
        processOverdueClaims(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    public int processOverdueClaims(Instant now) {
        return warrantyClaimService.escalateOverdueWorkmanshipClaims(now, BATCH_SIZE);
    }
}
