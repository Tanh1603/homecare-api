package com.homecare.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.common.error.DomainException;
import com.homecare.notification.dto.NotificationDtos.NotificationResponse;
import com.homecare.notification.dto.NotificationDtos.RegisterDeviceRequest;
import com.homecare.notification.repository.ProcessedEventRepository;

@SpringBootTest
@ActiveProfiles("test")
class NotificationInboxServiceTest {

    @Autowired
    private NotificationInboxService inboxService;

    @Autowired
    private DomainEventConsumer consumer;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void deduplicatesDomainEventsPersistsProcessedEventsAndEnforcesIdempotentMarkAsReadAndTokenOwnership() {
        String eventJson = """
                {"eventId":"evt-1001","eventType":"booking.assigned.v1","data":{"requesterProfileId":"cust-1","technicianProfileId":"tech-1","serviceName":"Vệ sinh Máy lạnh"}}
                """;
        consumer.onDomainEvent(eventJson);
        consumer.onDomainEvent(eventJson); // duplicate delivery

        List<NotificationResponse> custInbox = inboxService.listInbox("cust-1");
        assertThat(custInbox).hasSize(1);
        assertThat(custInbox.getFirst().read()).isFalse();
        assertThat(processedEventRepository.findByConsumerNameAndEventId(
                NotificationInboxService.CONSUMER_NAME + ":cust-1", "evt-1001")).isPresent();

        NotificationResponse readOnce = inboxService.markAsRead("cust-1", custInbox.getFirst().id());
        NotificationResponse readTwice = inboxService.markAsRead("cust-1", custInbox.getFirst().id());
        assertThat(readOnce.read()).isTrue();
        assertThat(readTwice.readAt()).isEqualTo(readOnce.readAt());

        inboxService.registerDevice("cust-1", new RegisterDeviceRequest("ANDROID", "fcm-token-xyz"));

        // Triggering another event for cust-1 after token is registered exercises FirebaseMessagingService push
        String event2Json = """
                {"eventId":"evt-1002","eventType":"booking.completed.v1","data":{"requesterProfileId":"cust-1","serviceName":"Vệ sinh Máy lạnh"}}
                """;
        consumer.onDomainEvent(event2Json);
        assertThat(inboxService.listInbox("cust-1")).hasSize(2);

        assertThatThrownBy(() -> inboxService.registerDevice(
                "cust-2", new RegisterDeviceRequest("ANDROID", "fcm-token-xyz")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("TOKEN_ALREADY_REGISTERED");
    }
}
