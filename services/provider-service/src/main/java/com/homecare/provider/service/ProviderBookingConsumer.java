package com.homecare.provider.service;

import com.homecare.common.event.DomainEventContracts;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class ProviderBookingConsumer {
    private static final Logger log = LoggerFactory.getLogger(ProviderBookingConsumer.class);
    public static final String QUEUE = "provider.booking.release.q";

    private final ProviderDomainService service;
    private final ObjectMapper mapper;

    @RabbitListener(queues = QUEUE)
    public void onEvent(String payload) {
        var event = mapper.readTree(payload);
        String type = event.path(DomainEventContracts.FIELD_EVENT_TYPE).asString();
        if (!DomainEventContracts.BOOKING_COMPLETED.equals(type)
                && !DomainEventContracts.BOOKING_CANCELLED.equals(type)
                && !DomainEventContracts.BOOKING_ALLOCATION_RELEASE_RETRY.equals(type)) {
            return;
        }
        String bookingIdText = event.path(DomainEventContracts.FIELD_DATA).path("bookingId").asString(null);
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
        if (DomainEventContracts.BOOKING_COMPLETED.equals(type)) {
            service.completeBooking(bookingId);
        } else {
            service.releaseBooking(bookingId);
        }
    }
}
