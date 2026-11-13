package com.homecare.provider.organization.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OrganizationOutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OrganizationOutboxRelayWorker.class);

    private final ProviderOutboxEventRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelayString = "${homecare.organization.outbox-poll-ms:5000}")
    @Transactional
    public void relayPendingEvents() {
        Instant now = Instant.now();
        List<ProviderOutboxEventEntity> pending = outboxRepository.findPendingDueEvents(now);
        for (ProviderOutboxEventEntity event : pending) {
            try {
                rabbitTemplate.convertAndSend(
                        DomainEventContracts.EXCHANGE,
                        event.getRoutingKey(),
                        event.getPayloadJson());
                event.setStatus(EntityStatus.PUBLISHED.name());
                event.setPublishedAt(Instant.now());
                outboxRepository.save(event);
            } catch (Exception ex) {
                int nextRetry = event.getRetryCount() + 1;
                long backoffSeconds = Math.min(300L, nextRetry * 15L);
                log.warn("Deferred publish for organization outbox event {}: {}", event.getId(), ex.getMessage());
                event.setRetryCount(nextRetry);
                event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
                outboxRepository.save(event);
            }
        }
    }
}
