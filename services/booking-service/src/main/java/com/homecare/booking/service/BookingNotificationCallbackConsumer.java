package com.homecare.booking.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.homecare.common.event.DomainEventContracts;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes notification.booking.completion.# delivery proof callbacks from notification-service (D-11.1, D-27).
 */
@Component
@RequiredArgsConstructor
public class BookingNotificationCallbackConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingNotificationCallbackConsumer.class);
    public static final String QUEUE = "booking.notification.callback.q";

    private final BookingAcceptanceService acceptanceService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE)
    public void onNotificationCallback(String payloadJson) {
        try {
            var root = objectMapper.readTree(payloadJson);
            String eventType = root.path(DomainEventContracts.FIELD_EVENT_TYPE).asString("");
            var data = root.path(DomainEventContracts.FIELD_DATA);
            String bookingIdRaw = data.path("bookingId").asString(null);
            if (bookingIdRaw == null || bookingIdRaw.isBlank()) {
                return;
            }
            UUID bookingId = UUID.fromString(bookingIdRaw);
            if (DomainEventContracts.NOTIFICATION_COMPLETION_DELIVERED.equals(eventType)) {
                String deliveredAtRaw = data.path("deliveredAt").asString(null);
                Instant deliveredAt = deliveredAtRaw != null && !deliveredAtRaw.isBlank()
                        ? Instant.parse(deliveredAtRaw)
                        : Instant.now();
                acceptanceService.recordCompletionNotificationDelivered(bookingId, deliveredAt);
            } else if (DomainEventContracts.NOTIFICATION_COMPLETION_FAILED.equals(eventType)) {
                String reason = data.path("reason").asString("Delivery failed after 24h retry");
                acceptanceService.recordCompletionNotificationFailed(bookingId, reason);
            }
        } catch (Exception e) {
            log.warn("Failed to process notification callback payload: {}", e.getMessage());
            throw new IllegalStateException("Failed to process notification callback", e);
        }
    }
}
