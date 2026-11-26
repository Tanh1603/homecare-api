package com.homecare.household.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.household.entity.HouseholdOutboxEvent;
import com.homecare.household.repository.HouseholdOutboxEventRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MaintenanceReminderWorker {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceReminderWorker.class);
    static final int MAX_RETRIES = 10;
    public static final String EXCHANGE = DomainEventContracts.EXCHANGE;
    public static final String ROUTING_KEY = DomainEventContracts.MAINTENANCE_REMINDER_DUE;

    private final HouseholdDomainService domainService;
    private final HouseholdOutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelayString = "${homecare.maintenance.poll-ms:60000}")
    @Transactional
    public void emitDueMaintenanceReminders() {
        domainService.enqueueDueMaintenanceReminders(LocalDate.now(HouseholdDomainService.VIETNAM_ZONE));
        List<HouseholdOutboxEvent> pending = outboxEventRepository.findPendingDueEvents(Instant.now());
        for (HouseholdOutboxEvent event : pending) {
            try {
                rabbitTemplate.convertAndSend(EXCHANGE, event.getRoutingKey(), event.getPayloadJson());
                event.setStatus(EntityStatus.PUBLISHED.name());
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                int nextRetry = event.getRetryCount() + 1;
                event.setRetryCount(nextRetry);
                if (nextRetry >= MAX_RETRIES) {
                    event.setStatus(EntityStatus.DEAD.name());
                    event.setNextAttemptAt(null);
                    log.error("Household outbox event {} exhausted {} retries -> DEAD: {}", event.getId(), MAX_RETRIES, e.getMessage());
                } else {
                    log.warn("RabbitMQ publish deferred for outbox event {}: {}", event.getId(), e.getMessage());
                    event.setNextAttemptAt(Instant.now().plusSeconds(Math.min(300L, nextRetry * 15L)));
                }
                outboxEventRepository.save(event);
            }
        }
    }
}
