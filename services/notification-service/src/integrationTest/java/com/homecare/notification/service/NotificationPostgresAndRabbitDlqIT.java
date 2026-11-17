package com.homecare.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.homecare.common.event.DomainEventContracts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class NotificationPostgresAndRabbitDlqIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @Autowired
    NotificationInboxService inboxService;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    Queue notificationInboxDlq;

    @Test
    void deduplicatesNotificationsAndRoutesPoisonMessageToDlq() throws Exception {
        String eventId = "pg-evt-" + UUID.randomUUID();
        inboxService.recordIfAbsent(eventId, "pg-user", "booking.requested.v1", "Title", "Body");
        inboxService.recordIfAbsent(eventId, "pg-user", "booking.requested.v1", "Title", "Body");
        assertThat(inboxService.inboxPage("pg-user", 1, 20).items()).hasSize(1);

        rabbitTemplate.convertAndSend(DomainEventContracts.EXCHANGE, "booking.poison.v1", "not-json");
        Message dlqMsg = null;
        for (int i = 0; i < 20 && dlqMsg == null; i++) {
            dlqMsg = rabbitTemplate.receive(notificationInboxDlq.getName(), 500);
        }
        assertThat(dlqMsg).isNotNull();
    }
}
