package com.homecare.booking.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingPurpose;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.common.event.DomainEventContracts;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class CommerceInstallationConsumer {

    public static final String QUEUE = "booking.commerce.installation.q";

    private final BookingLifecycleService bookingLifecycleService;
    private final BookingRepository bookingRepository;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE)
    @SuppressWarnings("unchecked")
    public void onDomainEvent(String payloadJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(payloadJson, Map.class);
            String eventType = String.valueOf(root.getOrDefault(DomainEventContracts.FIELD_EVENT_TYPE, ""));
            Map<String, Object> data = root.get(DomainEventContracts.FIELD_DATA) instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : Map.of();
            if ("ORDER_LINE_DELIVERED".equals(eventType)) {
                UUID orderLineId = UUID.fromString(String.valueOf(data.get("orderLineId")));
                bookingRepository.findByOrderLineIdAndPurpose(orderLineId, BookingPurpose.INSTALLATION.name())
                        .ifPresent(booking -> {
                            booking.setProductReady(true);
                            booking.setUpdatedAt(Instant.now());
                            bookingRepository.save(booking);
                        });
                return;
            }
            if (!"ORDER_INSTALLATION_REQUESTED".equals(eventType)) {
                return;
            }
            UUID orderLineId = UUID.fromString(String.valueOf(data.get("orderLineId")));
            if (bookingRepository.findByOrderLineIdAndPurpose(orderLineId, BookingPurpose.INSTALLATION.name()).isPresent()) {
                return;
            }
            UUID serviceOfferingId = UUID.fromString(String.valueOf(data.get("serviceOfferingId")));
            UUID serviceAddressId = UUID.fromString(String.valueOf(data.get("serviceAddressId")));
            String buyerProfileId = String.valueOf(data.get("buyerProfileId"));
            Instant preferredStart = data.get("preferredInstallStart") == null
                    ? Instant.now().plusSeconds(86_400)
                    : Instant.parse(String.valueOf(data.get("preferredInstallStart")));
            var created = bookingLifecycleService.createBooking(
                    buyerProfileId,
                    String.valueOf(data.get("buyerName")),
                    new CreateBookingRequest(
                            serviceOfferingId,
                            null,
                            String.valueOf(data.get("productName")),
                            preferredStart,
                            null,
                            null,
                            "Installation for order " + data.get("orderId"),
                            "INSTALLATION",
                            null,
                            null,
                            serviceAddressId));
            BookingEntity booking = bookingRepository.findById(created.id()).orElseThrow();
            booking.setOrderId(UUID.fromString(String.valueOf(data.get("orderId"))));
            booking.setOrderLineId(orderLineId);
            booking.setPurpose(BookingPurpose.INSTALLATION.name());
            booking.setProductReady(false);
            bookingRepository.save(booking);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create installation booking", ex);
        }
    }
}

@Configuration
class CommerceInstallationRabbitConfiguration {
    @Bean
    Queue commerceInstallationQueue() {
        return new Queue(CommerceInstallationConsumer.QUEUE, true);
    }

    @Bean
    Binding commerceInstallationBinding(Queue commerceInstallationQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(commerceInstallationQueue)
                .to(domainEventsExchange)
                .with("ORDER_INSTALLATION_REQUESTED");
    }

    @Bean
    Binding commerceInstallationDeliveryBinding(
            Queue commerceInstallationQueue,
            TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(commerceInstallationQueue)
                .to(domainEventsExchange)
                .with("ORDER_LINE_DELIVERED");
    }
}
