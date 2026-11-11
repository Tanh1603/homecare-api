package com.homecare.commerce.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.commerce.entity.CommerceOutboxEventEntity;
import com.homecare.commerce.repository.CommerceOutboxEventRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "homecare.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class CommerceOutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(CommerceOutboxRelayWorker.class);

    private final CommerceOutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelayString = "${homecare.outbox.relay-interval-ms:10000}")
    @Transactional
    public void relayPendingEvents() {
        List<CommerceOutboxEventEntity> pending = repository.findByStatusOrderByCreatedAtAsc("PENDING");
        if (pending.isEmpty()) {
            return;
        }
        for (CommerceOutboxEventEntity event : pending) {
            try {
                rabbitTemplate.convertAndSend(CommerceDomainService.EXCHANGE, event.getRoutingKey(), event.getPayloadJson());
                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                repository.save(event);
            } catch (Exception e) {
                log.warn("Commerce outbox relay skipped/failed for event {}: {}", event.getId(), e.getMessage());
                break;
            }
        }
    }
}
