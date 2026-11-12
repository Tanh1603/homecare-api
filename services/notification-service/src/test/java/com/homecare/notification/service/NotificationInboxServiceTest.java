package com.homecare.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.notification.dto.NotificationDtos.DeviceRegistrationResponse;
import com.homecare.notification.dto.NotificationDtos.NotificationResponse;
import com.homecare.notification.dto.NotificationDtos.RegisterDeviceRequest;
import com.homecare.notification.repository.DeviceRegistrationRepository;
import com.homecare.notification.repository.ProcessedEventRepository;

@SpringBootTest
@ActiveProfiles("test")
class NotificationInboxServiceTest {

    @Autowired
    private NotificationInboxService inboxService;

    @Autowired
    private DomainEventConsumer consumer;

    @Autowired
    private FcmPushConsumer fcmPushConsumer;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private DeviceRegistrationRepository deviceRegistrationRepository;

    @Test
    void deduplicatesDomainEventsPersistsProcessedEventsAndEnforcesIdempotentMarkAsReadAndTokenOwnershipTransfer() {
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
        assertThat(deviceRegistrationRepository.findByRecipientIdAndActiveTrue("cust-1")).hasSize(1);

        // Triggering another event for cust-1 after token is registered exercises RabbitMQ push event enqueueing with entity ID
        String event2Json = """
                {"eventId":"evt-1002","eventType":"booking.completed.v1","data":{"bookingId":"bkg-999","requesterProfileId":"cust-1","serviceName":"Vệ sinh Máy lạnh"}}
                """;
        consumer.onDomainEvent(event2Json);
        assertThat(inboxService.listInbox("cust-1")).hasSize(2);

        // Verify account switching on same device transfers token ownership to cust-2
        DeviceRegistrationResponse transferred = inboxService.registerDevice(
                "cust-2", new RegisterDeviceRequest("ANDROID", "fcm-token-xyz"));
        assertThat(transferred.recipientId()).isEqualTo("cust-2");
        assertThat(transferred.active()).isTrue();
        assertThat(deviceRegistrationRepository.findByRecipientIdAndActiveTrue("cust-1")).isEmpty();
        assertThat(deviceRegistrationRepository.findByRecipientIdAndActiveTrue("cust-2")).hasSize(1);

        // Verify FcmPushConsumer processes push dispatch message safely
        String pushDispatchJson = """
                {"recipientId":"cust-2","title":"Thông báo mới","body":"Kỹ thuật viên đã đến","data":{"bookingId":"b-123"}}
                """;
        fcmPushConsumer.onPushDispatch(pushDispatchJson);
    }
}
