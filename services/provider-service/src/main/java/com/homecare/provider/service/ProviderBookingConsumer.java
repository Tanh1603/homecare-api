package com.homecare.provider.service;

import com.homecare.common.event.DomainEventContracts;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class ProviderBookingConsumer {
    private static final Logger log = LoggerFactory.getLogger(ProviderBookingConsumer.class);
    public static final String QUEUE = "provider.booking.release.q";
    private final ProviderDomainService service;
    private final ObjectMapper mapper;
    @Bean Queue providerBookingQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DomainEventContracts.DLX)
                .withArgument("x-dead-letter-routing-key", DomainEventContracts.dlqName(QUEUE))
                .build();
    }
    @Bean Queue providerBookingDlq() { return QueueBuilder.durable(DomainEventContracts.dlqName(QUEUE)).build(); }
    @Bean Binding bindProviderBookingDlq(Queue providerBookingDlq, DirectExchange providerEventsDlx) {
        return BindingBuilder.bind(providerBookingDlq).to(providerEventsDlx).with(DomainEventContracts.dlqName(QUEUE));
    }
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
        UUID bookingId;
        try {
            bookingId = UUID.fromString(bookingIdText);
        } catch (IllegalArgumentException ex) {
            log.warn("Ignoring {} event with invalid bookingId='{}'", type, bookingIdText);
            return;
        }
        if ("booking.completed.v1".equals(type)) {
            service.completeBooking(bookingId);
        } else {
            service.releaseBooking(bookingId);
        }
    }
}
