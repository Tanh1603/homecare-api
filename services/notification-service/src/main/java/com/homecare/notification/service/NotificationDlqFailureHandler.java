package com.homecare.notification.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.homecare.common.event.DomainEventContracts;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Emits technical completion notification failure callbacks when messages exhaust broker retries
 * and land on notification.inbox.q.dlq (D-27). Never throws so poison DLQ messages never loop.
 */
@Component
@RequiredArgsConstructor
public class NotificationDlqFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDlqFailureHandler.class);
    public static final String DLQ = DomainEventConsumer.QUEUE + ".dlq";

    private final ObjectMapper objectMapper;
    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;

    @RabbitListener(queues = DLQ)
    public void onDeadLetteredEvent(String payloadJson) {
        try {
            var root = objectMapper.readTree(payloadJson);
            String eventType = root.path(DomainEventContracts.FIELD_EVENT_TYPE).asString("");
            if (!DomainEventContracts.BOOKING_COMPLETION_PENDING_ACCEPTANCE.equals(eventType)) {
                return;
            }
            String bookingId = root.path(DomainEventContracts.FIELD_DATA).path("bookingId").asString("");
            if (bookingId.isBlank()) {
                return;
            }
            RabbitTemplate template = rabbitTemplateProvider.getIfAvailable();
            if (template == null) {
                return;
            }
            Instant now = Instant.now();
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString());
            envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.NOTIFICATION_COMPLETION_FAILED);
            envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
            envelope.put(DomainEventContracts.FIELD_PRODUCER, "notification-service");
            envelope.put(DomainEventContracts.FIELD_DATA, Map.of(
                    "bookingId", bookingId,
                    "failedAt", now.toString(),
                    "reason", "Broker delivery retries exhausted and routed to DLQ"));

            template.convertAndSend(
                    DomainEventContracts.EXCHANGE,
                    DomainEventContracts.NOTIFICATION_COMPLETION_FAILED,
                    objectMapper.writeValueAsString(envelope));
        } catch (Exception ex) {
            log.warn("Ignored/logged dead-lettered notification payload without requeue: {}", ex.getMessage());
        }
    }
}
