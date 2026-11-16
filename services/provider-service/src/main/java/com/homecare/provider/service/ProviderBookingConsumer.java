package com.homecare.provider.service;

import com.homecare.common.event.DomainEventContracts;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class ProviderBookingConsumer {
    public static final String QUEUE = "provider.booking.release.q";
    private final ProviderDomainService service;
    private final ObjectMapper mapper;
    @Bean Queue providerBookingQueue() { return new Queue(QUEUE, true); }
    @Bean Binding providerBookingCompleted() { return new Binding(QUEUE, Binding.DestinationType.QUEUE, DomainEventContracts.EXCHANGE, "booking.completed.v1", null); }
    @Bean Binding providerBookingCancelled() { return new Binding(QUEUE, Binding.DestinationType.QUEUE, DomainEventContracts.EXCHANGE, "booking.cancelled.v1", null); }
    @RabbitListener(queues = QUEUE)
    public void onEvent(String payload) {
        var event = mapper.readTree(payload);
        String type = event.path("eventType").asString();
        if (!"booking.completed.v1".equals(type) && !"booking.cancelled.v1".equals(type)) {
            return;
        }
        String bookingIdText = event.path("data").path("bookingId").asString(null);
        if (bookingIdText == null || bookingIdText.isBlank()) {
            return;
        }
        UUID bookingId = UUID.fromString(bookingIdText);
        if ("booking.completed.v1".equals(type)) {
            service.completeBooking(bookingId);
        } else {
            service.releaseBooking(bookingId);
        }
    }
}
