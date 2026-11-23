package com.homecare.booking.service;

import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.entity.ServiceRequestEntity;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.ServiceRequestRepository;
import com.homecare.common.event.DomainEventContracts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles provider & user governance domain events in booking-service (D-25, D-29).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderGovernanceConsumer {

    public static final String QUEUE = "booking.provider.governance.q";

    private static final List<String> OPEN_BOOKING_STATUSES = List.of(
            BookingStatus.REQUESTED.name(),
            BookingStatus.ASSIGNED.name(),
            BookingStatus.IN_PROGRESS.name(),
            "WAITING_FOR_PARTS",
            "COMPLETED_PENDING_ACCEPTANCE",
            "REVISION_REQUESTED");

    private final ServiceRequestRepository serviceRequestRepository;
    private final BookingRepository bookingRepository;
    private final ProviderGrpcClientAdapter providerGrpcClient;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE)
    @Transactional
    @SuppressWarnings("unchecked")
    public void onGovernanceEvent(String payloadJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(payloadJson, Map.class);
            String eventType = String.valueOf(root.getOrDefault(DomainEventContracts.FIELD_EVENT_TYPE, ""));
            Map<String, Object> data = root.get(DomainEventContracts.FIELD_DATA) instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : Map.of();
            switch (eventType) {
                case DomainEventContracts.OFFERING_DEACTIVATED -> handleOfferingDeactivated(data);
                case DomainEventContracts.ORG_SUSPENDED, DomainEventContracts.ORG_BANNED -> handleOrgSanctioned(eventType, data);
                case DomainEventContracts.USER_SUSPENDED -> handleUserSuspended(data);
                default -> { }
            }
        } catch (Exception ex) {
            log.warn("Failed to process governance event: {}", ex.getMessage());
            throw new IllegalStateException("Failed to process governance event", ex);
        }
    }

    private void handleOfferingDeactivated(Map<String, Object> data) {
        UUID offeringId = parseUuid(data.get("serviceOfferingId"));
        if (offeringId == null) {
            return;
        }
        String reason = String.valueOf(data.getOrDefault("reason", "OFFERING_DEACTIVATED"));
        Instant now = Instant.now();
        List<ServiceRequestEntity> awaiting = serviceRequestRepository.findByServiceOfferingIdAndStatusIn(
                offeringId, List.of("AWAITING_PROVIDER"));
        for (ServiceRequestEntity sr : awaiting) {
            sr.setStatus("AWAITING_ALTERNATIVE");
            sr.setFallbackReason(reason);
            sr.setFallbackNotifiedAt(now);
            sr.setUpdatedAt(now);
            serviceRequestRepository.save(sr);
        }
    }

    private void handleOrgSanctioned(String eventType, Map<String, Object> data) {
        UUID providerId = parseUuid(data.get("providerId"));
        if (providerId == null) {
            providerId = parseUuid(data.get("organizationId"));
        }
        if (providerId == null) {
            return;
        }
        String reason = String.valueOf(data.getOrDefault("reason", eventType));
        boolean safetyIntervention = Boolean.TRUE.equals(data.get("safetyInterventionFlag"))
                || "true".equalsIgnoreCase(String.valueOf(data.get("safetyInterventionFlag")));
        Instant now = Instant.now();

        List<ServiceRequestEntity> pendingSrs = serviceRequestRepository.findByAssignedProviderIdAndStatusIn(
                providerId, List.of("AWAITING_PROVIDER", "QUOTATION_SENT"));
        for (ServiceRequestEntity sr : pendingSrs) {
            sr.setStatus("AWAITING_ALTERNATIVE");
            sr.setFallbackReason(eventType);
            sr.setFallbackNotifiedAt(now);
            sr.setUpdatedAt(now);
            serviceRequestRepository.save(sr);
        }

        List<BookingEntity> preArrived = bookingRepository.findByProviderIdAndStatusIn(
                providerId, List.of(BookingStatus.REQUESTED.name(), BookingStatus.ASSIGNED.name()));
        for (BookingEntity booking : preArrived) {
            booking.setStatus(BookingStatus.CANCELLED.name());
            booking.setAllocationStatus("RELEASED");
            booking.setQuotedPrice(0L);
            booking.setUpdatedAt(now);
            bookingRepository.save(booking);
            if (booking.getActiveAllocationOperationId() != null) {
                try {
                    providerGrpcClient.releaseBooking(
                            booking.getId(), booking.getActiveAllocationOperationId(), eventType);
                } catch (Exception ex) {
                    log.warn("ReleaseBooking skipped/failed for sanctioned provider booking {}: {}",
                            booking.getId(), ex.getMessage());
                }
            }
            if (booking.getServiceRequestId() != null) {
                serviceRequestRepository.findById(booking.getServiceRequestId()).ifPresent(sr -> {
                    sr.setStatus("AWAITING_ALTERNATIVE");
                    sr.setFallbackReason(eventType);
                    sr.setFallbackNotifiedAt(now);
                    sr.setUpdatedAt(now);
                    serviceRequestRepository.save(sr);
                });
            }
        }

        if (safetyIntervention) {
            List<BookingEntity> inProgress = bookingRepository.findByProviderIdAndStatusIn(
                    providerId, List.of(BookingStatus.IN_PROGRESS.name()));
            for (BookingEntity booking : inProgress) {
                booking.setAdminReviewFlagged(true);
                booking.setAdminReviewReason("SAFETY_INTERVENTION: " + reason);
                booking.setUpdatedAt(now);
                bookingRepository.save(booking);
            }
        }
    }

    private void handleUserSuspended(Map<String, Object> data) {
        Object rawUserId = data.get("userId");
        if (rawUserId == null) {
            return;
        }
        String userId = String.valueOf(rawUserId);
        String reason = String.valueOf(data.getOrDefault("reason", "USER_SUSPENDED"));
        Instant now = Instant.now();
        List<BookingEntity> openBookings = bookingRepository.findOpenBookingsForUser(userId, OPEN_BOOKING_STATUSES);
        for (BookingEntity booking : openBookings) {
            booking.setAdminReviewFlagged(true);
            booking.setAdminReviewReason("USER_SUSPENDED: " + reason);
            booking.setUpdatedAt(now);
            bookingRepository.save(booking);
        }
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(raw));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Configuration
    static class RabbitBindingConfig {
        @Bean
        Queue bookingGovernanceQueue() {
            return new Queue(QUEUE, true);
        }

        @Bean
        Binding bookingGovernanceOrgSuspendedBinding(Queue bookingGovernanceQueue, TopicExchange homecareDomainExchange) {
            return BindingBuilder.bind(bookingGovernanceQueue).to(homecareDomainExchange).with(DomainEventContracts.ORG_SUSPENDED);
        }

        @Bean
        Binding bookingGovernanceOrgBannedBinding(Queue bookingGovernanceQueue, TopicExchange homecareDomainExchange) {
            return BindingBuilder.bind(bookingGovernanceQueue).to(homecareDomainExchange).with(DomainEventContracts.ORG_BANNED);
        }

        @Bean
        Binding bookingGovernanceOfferingDeactivatedBinding(Queue bookingGovernanceQueue, TopicExchange homecareDomainExchange) {
            return BindingBuilder.bind(bookingGovernanceQueue).to(homecareDomainExchange).with(DomainEventContracts.OFFERING_DEACTIVATED);
        }

        @Bean
        Binding bookingGovernanceUserSuspendedBinding(Queue bookingGovernanceQueue, TopicExchange homecareDomainExchange) {
            return BindingBuilder.bind(bookingGovernanceQueue).to(homecareDomainExchange).with(DomainEventContracts.USER_SUSPENDED);
        }
    }
}
