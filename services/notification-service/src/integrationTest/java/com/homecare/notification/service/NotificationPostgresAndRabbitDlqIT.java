package com.homecare.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.homecare.common.event.DomainEventContracts;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers
class NotificationPostgresAndRabbitDlqIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @Autowired NotificationInboxService inboxService;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired TopicExchange domainEventsExchange;
    @Autowired ObjectMapper objectMapper;

    @MockitoSpyBean NotificationDlqFailureHandler dlqFailureHandler;
    @MockitoSpyBean NotificationInboxService spyInboxService;

    @Test
    void validatesFlywaySchemaDeduplicatesEventsConsumes006AndEmitsDlqFailureCallback() throws Exception {
        Queue failedSpyQueue = QueueBuilder.durable("test.completion.failed.spy.q").build();
        amqpAdmin.declareQueue(failedSpyQueue);
        Binding failedSpyBinding = BindingBuilder.bind(failedSpyQueue)
                .to(domainEventsExchange)
                .with(DomainEventContracts.NOTIFICATION_COMPLETION_FAILED);
        amqpAdmin.declareBinding(failedSpyBinding);
        // 1. Deduplication check on Flyway-validated PostgreSQL schema
        String eventId = "pg-evt-" + UUID.randomUUID();
        inboxService.recordIfAbsent(eventId, "pg-user", "booking.requested.v1", "Title", "Body");
        inboxService.recordIfAbsent(eventId, "pg-user", "booking.requested.v1", "Title", "Body");
        assertThat(inboxService.inboxPage("pg-user", 1, 20).items()).hasSize(1);

        // 2. Poison payload routes to notification.inbox.q.dlq and is handled cleanly without requeue loop
        rabbitTemplate.convertAndSend(DomainEventContracts.EXCHANGE, "booking.poison.v1", "not-json");
        verify(dlqFailureHandler, timeout(10_000)).onDeadLetteredEvent("not-json");

        // 3. Exhausted retries on booking.completion.pending_acceptance.v1 -> DLQ emits failed.v1
        String failingBookingId = UUID.randomUUID().toString();
        String failingEventId = "fail-evt-" + UUID.randomUUID();
        String failingRecipient = "cust-dlq-" + UUID.randomUUID();
        doThrow(new RuntimeException("Simulated inbox write failure"))
                .when(spyInboxService).recordIfAbsent(
                        eq(failingEventId),
                        eq(failingRecipient),
                        eq(DomainEventContracts.BOOKING_COMPLETION_PENDING_ACCEPTANCE),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        anyMap());

        sendEvent(DomainEventContracts.BOOKING_COMPLETION_PENDING_ACCEPTANCE, failingEventId, Map.of(
                "bookingId", failingBookingId,
                "requesterProfileId", failingRecipient,
                "serviceName", "Sua may lanh"));

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            Object raw = rabbitTemplate.receiveAndConvert("test.completion.failed.spy.q", 500);
            assertThat(raw).isNotNull();
            String body = String.valueOf(raw);
            assertThat(body).contains(DomainEventContracts.NOTIFICATION_COMPLETION_FAILED);
            assertThat(body).contains(failingBookingId);
        });

        // 4. D-10 copy fix + all 6 Spec 006 parts/warranty events over RabbitMQ
        String cust = "cust-006-" + UUID.randomUUID();
        String tech = "tech-006-" + UUID.randomUUID();
        String orgOp = "org-006-" + UUID.randomUUID();
        String admin = "admin-006-" + UUID.randomUUID();
        String bookingId = UUID.randomUUID().toString();
        String claimId = UUID.randomUUID().toString();

        sendEvent(DomainEventContracts.BOOKING_ASSIGNED, "evt-assigned-" + UUID.randomUUID(), Map.of(
                "bookingId", bookingId,
                "requesterProfileId", cust,
                "technicianProfileId", tech,
                "serviceName", "Sua tu lanh"));
        sendEvent(DomainEventContracts.PARTS_REQUEST_CREATED, "evt-pr1-" + UUID.randomUUID(), Map.of(
                "partsRequestId", UUID.randomUUID().toString(),
                "bookingId", bookingId,
                "providerId", UUID.randomUUID().toString(),
                "providerRecipientProfileIds", List.of(orgOp),
                "partName", "Tu lanh Block"));
        sendEvent(DomainEventContracts.PARTS_REQUEST_REVIEWED, "evt-pr2-" + UUID.randomUUID(), Map.of(
                "partsRequestId", UUID.randomUUID().toString(),
                "bookingId", bookingId,
                "requestedByProfileId", tech,
                "partName", "Tu lanh Block",
                "status", "PROVIDER_APPROVED"));
        sendEvent(DomainEventContracts.WARRANTY_CLAIM_SUBMITTED, "evt-wc1-" + UUID.randomUUID(), Map.of(
                "claimId", claimId,
                "bookingId", bookingId,
                "providerId", UUID.randomUUID().toString(),
                "providerRecipientProfileIds", List.of(orgOp),
                "adminProfileIds", List.of(admin),
                "warrantyCategory", "PARTS",
                "claimStatus", "PENDING_VERIFICATION"));
        sendEvent(DomainEventContracts.WARRANTY_CLAIM_VERIFIED, "evt-wc2-" + UUID.randomUUID(), Map.of(
                "claimId", claimId,
                "bookingId", bookingId,
                "requesterProfileId", cust,
                "providerRecipientProfileIds", List.of(orgOp),
                "claimStatus", "PENDING_PROVIDER_RESPONSE"));
        sendEvent(DomainEventContracts.WARRANTY_CLAIM_RESPONDED, "evt-wc3-" + UUID.randomUUID(), Map.of(
                "claimId", claimId,
                "bookingId", bookingId,
                "requesterProfileId", cust,
                "reworkBookingId", UUID.randomUUID().toString(),
                "claimStatus", "REWORK_CREATED"));
        sendEvent(DomainEventContracts.WARRANTY_CLAIM_SLA_ESCALATED, "evt-wc4-" + UUID.randomUUID(), Map.of(
                "claimId", claimId,
                "bookingId", bookingId,
                "requesterProfileId", cust,
                "providerNoResponse", true));

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var custInbox = inboxService.inboxPage(cust, 1, 20).items();
            assertThat(custInbox).hasSize(4); // booking.assigned + verified + responded + sla_escalated
            assertThat(custInbox).allSatisfy(n -> assertThat(n.title()).doesNotContain("AI"));
            assertThat(inboxService.inboxPage(tech, 1, 20).items()).hasSize(2);
            assertThat(inboxService.inboxPage(orgOp, 1, 20).items()).hasSize(3);
            assertThat(inboxService.inboxPage(admin, 1, 20).items()).hasSize(1);
        });
    }

    private void sendEvent(String eventType, String eventId, Map<String, Object> data) throws Exception {
        var envelope = Map.of(
                DomainEventContracts.FIELD_EVENT_ID, eventId,
                DomainEventContracts.FIELD_EVENT_TYPE, eventType,
                DomainEventContracts.FIELD_OCCURRED_AT, Instant.now().toString(),
                DomainEventContracts.FIELD_PRODUCER, "test-producer",
                DomainEventContracts.FIELD_DATA, data);
        rabbitTemplate.convertAndSend(
                DomainEventContracts.EXCHANGE,
                eventType,
                objectMapper.writeValueAsString(envelope));
    }
}
