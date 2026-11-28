package com.homecare.provider.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.common.event.DomainEventContracts;
import com.homecare.provider.repository.ProviderRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies monotonic Provider rating aggregate updates emitted by review-service (D-31).
 */
@Component
@RequiredArgsConstructor
public class ProviderReviewRatingConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProviderReviewRatingConsumer.class);
    public static final String QUEUE = "provider.review.rating.q";

    private final ProviderRepository providerRepository;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE)
    @Transactional
    public void onReviewRatingUpdated(String payloadJson) {
        try {
            var root = objectMapper.readTree(payloadJson);
            String eventType = root.path(DomainEventContracts.FIELD_EVENT_TYPE).asString("");
            if (!DomainEventContracts.REVIEW_RATING_UPDATED.equals(eventType)) {
                return;
            }
            var data = root.path(DomainEventContracts.FIELD_DATA);
            String providerIdRaw = data.path("providerId").asString(null);
            String calculatedAtRaw = data.path("calculatedAt").asString(null);
            if (providerIdRaw == null || providerIdRaw.isBlank()
                    || calculatedAtRaw == null || calculatedAtRaw.isBlank()) {
                return;
            }
            UUID providerId = UUID.fromString(providerIdRaw);
            Instant calculatedAt = Instant.parse(calculatedAtRaw);
            double ratingAvg = Math.max(0.0, Math.min(5.0, data.path("ratingAvg").asDouble(0.0)));
            int reviewCount = Math.max(0, data.path("reviewCount").asInt(0));
            int updatedRows = providerRepository.updateRatingIfNewer(
                    providerId, ratingAvg, reviewCount, calculatedAt);
            log.debug("Processed {} for provider {} (avg={}, count={}, updatedRows={})",
                    eventType, providerId, ratingAvg, reviewCount, updatedRows);
        } catch (IllegalArgumentException ex) {
            log.warn("Ignoring malformed review rating payload: {}", ex.getMessage());
        } catch (Exception ex) {
            log.warn("Failed to process review.rating.updated.v1 payload: {}", ex.getMessage());
            throw new IllegalStateException("Failed to process review rating sync", ex);
        }
    }
}
