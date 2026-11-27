package com.homecare.provider.service;

import com.homecare.provider.repository.ScheduleExceptionRepository;
import com.homecare.provider.repository.WeeklyScheduleRuleRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily rolling horizon replenisher and startup recovery sweep (BR-SCH-007, BR-SCH-050, BR-SCH-051).
 */
@Component
@RequiredArgsConstructor
public class ScheduleMaterializationWorker {

    private static final Logger log = LoggerFactory.getLogger(ScheduleMaterializationWorker.class);

    private final WeeklyScheduleRuleRepository weeklyRuleRepository;
    private final ScheduleExceptionRepository exceptionRepository;
    private final ScheduleReconciliationService reconciliationService;

    @Value("${homecare.schedule.startup-recovery-enabled:true}")
    private boolean startupRecoveryEnabled = true;

    @Scheduled(cron = "${homecare.schedule.materialization-cron:0 15 * * * *}", zone = "Asia/Ho_Chi_Minh")
    public void runDailyHorizonReplenishment() {
        replenishAllActiveTechnicians("SCHEDULED_CRON");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runStartupRecovery() {
        if (!startupRecoveryEnabled) {
            return;
        }
        replenishAllActiveTechnicians("STARTUP_RECOVERY");
    }

    public int replenishAllActiveTechnicians(String trigger) {
        ZoneId defaultZone = ZoneId.of(ScheduleReconciliationService.DEFAULT_TIMEZONE);
        LocalDate today = LocalDate.ofInstant(reconciliationService.now(), defaultZone);
        LocalDate horizonEnd = today.plusDays(reconciliationService.getHorizonDays() - 1L);
        Set<UUID> technicianIds = new LinkedHashSet<>(
                weeklyRuleRepository.findDistinctTechnicianIdsWithActiveOrUpcomingRules(today));
        technicianIds.addAll(
                exceptionRepository.findDistinctTechnicianIdsWithExceptionsBetween(today, horizonEnd));
        int processed = 0;
        for (UUID techId : technicianIds) {
            try {
                reconciliationService.reconcileHorizon(techId);
                processed++;
            } catch (Exception ex) {
                log.error("[SCHEDULE-WORKER] Failed to materialize schedule trigger={} technicianId={}",
                        trigger, techId, ex);
            }
        }
        if (processed > 0) {
            log.info("[SCHEDULE-WORKER] Completed trigger={} technicians={}", trigger, processed);
        }
        return processed;
    }
}
