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
        String type = event.path("eventType").asText();
        if ("booking.completed.v1".equals(type) || "booking.cancelled.v1".equals(type)) {
            service.releaseBooking(UUID.fromString(event.path("data").path("bookingId").asText()));
        }
    }
}
