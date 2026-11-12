package com.homecare.booking.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.repository.BookingOutboxEventRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "homecare.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class BookingOutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(BookingOutboxRelayWorker.class);

    private final BookingOutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelayString = "${homecare.outbox.relay-interval-ms:10000}")
    @Transactional
    public void relayPendingEvents() {
        List<BookingOutboxEventEntity> pending = repository.findByStatusOrderByCreatedAtAsc("PENDING");
        if (pending.isEmpty()) {
            return;
        }
        for (BookingOutboxEventEntity event : pending) {
            try {
                rabbitTemplate.convertAndSend(BookingLifecycleService.EXCHANGE, event.getRoutingKey(), event.getPayloadJson());
                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                repository.save(event);
            } catch (Exception e) {
                log.warn("Booking outbox relay skipped/failed for event {}: {}", event.getId(), e.getMessage());
                break;
            }
        }
    }
}
