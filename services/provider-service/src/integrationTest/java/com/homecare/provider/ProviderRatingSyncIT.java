package com.homecare.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.homecare.common.event.DomainEventContracts;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.service.ProviderDomainService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
class ProviderRatingSyncIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @Autowired ProviderRepository providerRepository;
    @Autowired ProviderDomainService providerDomainService;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper objectMapper;

    @BeforeAll
    static void migrate(@Autowired DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    @Test
    void appliesMonotonicRatingUpdatesFromRabbitAndIgnoresDuplicateAndStaleEvents() throws Exception {
        UUID providerId = UUID.randomUUID();
        providerRepository.saveAndFlush(ProviderEntity.builder()
                .id(providerId)
                .organizationId(UUID.randomUUID())
                .ownerProfileId("org-owner-" + providerId)
                .name("Dien Lanh Sao Viet")
                .description("Sua chua dien lanh")
                .city("Ho Chi Minh")
                .phone("0901234567")
                .ratingAvg(0.0)
                .reviewCount(0)
                .status("ACTIVE")
                .createdAt(Instant.now())
                .build());

        Instant tNewer = Instant.parse("2026-11-28T10:15:30.123456Z");
        Instant tOlder = tNewer.minusSeconds(30);

        sendRatingEvent(providerId, 4.5, 2, tNewer);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ProviderEntity synced = providerRepository.findById(providerId).orElseThrow();
            assertThat(synced.getRatingAvg()).isEqualTo(4.5);
            assertThat(synced.getReviewCount()).isEqualTo(2);
            assertThat(synced.getRatingCalculatedAt()).isEqualTo(tNewer);
        });

        // Duplicate same timestamp and stale older timestamp must NOT overwrite
        sendRatingEvent(providerId, 1.0, 99, tNewer);
        sendRatingEvent(providerId, 2.0, 1, tOlder);
        Thread.sleep(600);

        ProviderEntity afterStale = providerRepository.findById(providerId).orElseThrow();
        assertThat(afterStale.getRatingAvg()).isEqualTo(4.5);
        assertThat(afterStale.getReviewCount()).isEqualTo(2);

        // Search/listing exposes synced ratingAvg & reviewCount
        var found = providerDomainService.providersPage("Ho Chi Minh", "Sao Viet", 1, 10).items();
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().ratingAvg()).isEqualTo(4.5);
        assertThat(found.getFirst().reviewCount()).isEqualTo(2);
    }

    private void sendRatingEvent(UUID providerId, double avg, int count, Instant calculatedAt) throws Exception {
        var envelope = java.util.Map.of(
                DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString(),
                DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.REVIEW_RATING_UPDATED,
                DomainEventContracts.FIELD_OCCURRED_AT, calculatedAt.toString(),
                DomainEventContracts.FIELD_PRODUCER, "review-service",
                DomainEventContracts.FIELD_DATA, java.util.Map.of(
                        "providerId", providerId.toString(),
                        "ratingAvg", avg,
                        "reviewCount", count,
                        "calculatedAt", calculatedAt.toString()));
        rabbitTemplate.convertAndSend(
                DomainEventContracts.EXCHANGE,
                DomainEventContracts.REVIEW_RATING_UPDATED,
                objectMapper.writeValueAsString(envelope));
    }
}
