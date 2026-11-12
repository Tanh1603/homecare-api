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

import com.homecare.household.entity.HouseholdOutboxEvent;
import com.homecare.household.repository.HouseholdOutboxEventRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MaintenanceReminderWorker {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceReminderWorker.class);
    public static final String EXCHANGE = "homecare.domain.events";
    public static final String ROUTING_KEY = "maintenance.reminder.due.v1";

    private final HouseholdDomainService domainService;
    private final HouseholdOutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelayString = "${homecare.maintenance.poll-ms:60000}")
    @Transactional
    public void emitDueMaintenanceReminders() {
        domainService.enqueueDueMaintenanceReminders(LocalDate.now());
        List<HouseholdOutboxEvent> pending = outboxEventRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        for (HouseholdOutboxEvent event : pending) {
            try {
                rabbitTemplate.convertAndSend(EXCHANGE, event.getRoutingKey(), event.getPayloadJson());
                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.warn("RabbitMQ publish deferred for outbox event {}: {}", event.getId(), e.getMessage());
                event.setRetryCount(event.getRetryCount() + 1);
                event.setNextAttemptAt(Instant.now().plusSeconds(30));
                outboxEventRepository.save(event);
            }
        }
    }
}
