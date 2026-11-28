package com.homecare.review.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.homecare.common.event.DomainEventContracts;
import com.homecare.review.dto.ReviewDtos.ProviderRatingSnapshot;
import com.homecare.review.repository.ReviewRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Recomputes committed Provider rating aggregates in a fresh read-only transaction and publishes
 * review.rating.updated.v1 after commit without blocking or rolling back committed review writes (D-31).
 */
@Service
public class ReviewRatingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReviewRatingEventPublisher.class);

    private final ReviewRepository reviewRepository;
    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate readOnlyRequiresNewTx;
    private final AtomicReference<Instant> lastCalculatedAt = new AtomicReference<>(Instant.EPOCH);

    public ReviewRatingEventPublisher(
            ReviewRepository reviewRepository,
            ObjectProvider<RabbitTemplate> rabbitTemplateProvider,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.rabbitTemplateProvider = rabbitTemplateProvider;
        this.objectMapper = objectMapper;
        this.readOnlyRequiresNewTx = new TransactionTemplate(transactionManager);
        this.readOnlyRequiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnlyRequiresNewTx.setReadOnly(true);
    }

    public void scheduleAfterCommitPublish(UUID providerId) {
        if (providerId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recomputeAndPublishBestEffort(providerId);
                }
            });
        } else {
            recomputeAndPublishBestEffort(providerId);
        }
    }

    public ProviderRatingSnapshot recomputeAndPublishBestEffort(UUID providerId) {
        ProviderRatingSnapshot snapshot = computeCommittedSnapshot(providerId);
        publishSnapshotBestEffort(snapshot);
        return snapshot;
    }

    public ProviderRatingSnapshot computeCommittedSnapshot(UUID providerId) {
        Instant calculatedAt = lastCalculatedAt.accumulateAndGet(
                Instant.now(),
                (prev, cur) -> cur.isAfter(prev) ? cur : prev.plusNanos(1_000));
        return readOnlyRequiresNewTx.execute(status -> {
            List<Object[]> rows = reviewRepository.summarizePublishedServiceReviews(providerId);
            if (rows == null || rows.isEmpty()) {
                return new ProviderRatingSnapshot(providerId, 0.0, 0, calculatedAt);
            }
            Object[] row = rows.getFirst();
            long count = row[1] instanceof Number n ? n.longValue() : 0L;
            if (count <= 0) {
                return new ProviderRatingSnapshot(providerId, 0.0, 0, calculatedAt);
            }
            double rawAvg = row[0] instanceof Number n ? n.doubleValue() : 0.0;
            double roundedAvg = BigDecimal.valueOf(rawAvg)
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();
            return new ProviderRatingSnapshot(providerId, roundedAvg, (int) count, calculatedAt);
        });
    }

    public List<ProviderRatingSnapshot> resyncProviderRatings(UUID providerId) {
        if (providerId != null) {
            return List.of(recomputeAndPublishBestEffort(providerId));
        }
        List<UUID> providerIds = reviewRepository.findDistinctServiceProviderIds();
        return providerIds.stream()
                .map(this::recomputeAndPublishBestEffort)
                .toList();
    }

    private void publishSnapshotBestEffort(ProviderRatingSnapshot snapshot) {
        RabbitTemplate rabbitTemplate = rabbitTemplateProvider.getIfAvailable();
        if (rabbitTemplate == null) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("providerId", snapshot.providerId().toString());
            data.put("ratingAvg", snapshot.ratingAvg());
            data.put("reviewCount", snapshot.reviewCount());
            data.put("calculatedAt", snapshot.calculatedAt().toString());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString());
            envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.REVIEW_RATING_UPDATED);
            envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, snapshot.calculatedAt().toString());
            envelope.put(DomainEventContracts.FIELD_PRODUCER, "review-service");
            envelope.put(DomainEventContracts.FIELD_DATA, data);

            rabbitTemplate.convertAndSend(
                    DomainEventContracts.EXCHANGE,
                    DomainEventContracts.REVIEW_RATING_UPDATED,
                    objectMapper.writeValueAsString(envelope));
        } catch (Exception ex) {
            log.error("Failed to publish {} for provider {}: {}",
                    DomainEventContracts.REVIEW_RATING_UPDATED,
                    snapshot.providerId(),
                    ex.getMessage(),
                    ex);
        }
    }
}
