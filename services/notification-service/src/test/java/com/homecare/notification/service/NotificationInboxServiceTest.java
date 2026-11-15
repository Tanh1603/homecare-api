package com.homecare.notification.service;

import com.homecare.notification.repository.ProcessedEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.common.error.DomainException;
import com.homecare.notification.dto.NotificationDtos.DeviceRegistrationResponse;
import com.homecare.notification.dto.NotificationDtos.NotificationResponse;
import com.homecare.notification.dto.NotificationDtos.RegisterDeviceRequest;
import com.homecare.notification.repository.DeviceRegistrationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class NotificationInboxServiceTest {
    @Autowired WebApplicationContext webContext;

    @Test
    void cancelledBookingNotifiesBothRecipientsAndReadAllIsScoped() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String user = "cancel-customer-" + eventId;
        String tech = "cancel-tech-" + eventId;
        String event = """
            {"eventId":"%s","eventType":"booking.cancelled.v1","data":{"requesterProfileId":"%s","technicianProfileId":"%s","bookingId":"booking-1"}}
            """.formatted(eventId, user, tech);
        consumer.onDomainEvent(event); consumer.onDomainEvent(event);
        assertThat(inboxService.listInbox(user)).hasSize(1);
        assertThat(inboxService.listInbox(tech)).hasSize(1);
        var mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        var auth = SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(user));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/notifications/unread-count").with(auth))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.count").value(1));
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/notifications/read-all").with(auth))
                .andExpect(MockMvcResultMatchers.status().isOk());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/notifications/read-all").with(auth))
                .andExpect(MockMvcResultMatchers.status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/notifications/unread-count").with(auth))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.count").value(0));
        assertThat(inboxService.listInbox(tech).getFirst().read()).isFalse();
    }

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

        DeviceRegistrationResponse reg1 = inboxService.registerDevice(
                "cust-1", new RegisterDeviceRequest("ANDROID", "fcm-token-xyz"));
        assertThat(deviceRegistrationRepository.findByRecipientIdAndActiveTrue("cust-1")).hasSize(1);

        // Triggering another event for cust-1 after token is registered exercises RabbitMQ push event enqueueing with entity ID
        String event2Json = """
                {"eventId":"evt-1002","eventType":"booking.completed.v1","data":{"bookingId":"bkg-999","requesterProfileId":"cust-1","serviceName":"Vệ sinh Máy lạnh"}}
                """;
        consumer.onDomainEvent(event2Json);
        assertThat(inboxService.listInbox("cust-1")).hasSize(2);

        // Active token owned by cust-1 cannot be hijacked without prior deactivation
        assertThatThrownBy(() -> inboxService.registerDevice(
                "cust-2", new RegisterDeviceRequest("ANDROID", "fcm-token-xyz")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("TOKEN_ALREADY_REGISTERED");

        // Unsupported platform must be rejected
        assertThatThrownBy(() -> inboxService.registerDevice(
                "cust-1", new RegisterDeviceRequest("WINDOWS_PHONE", "fcm-token-bad")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");

        // Deactivate cust-1 device first, then cust-2 can legitimately claim the shared device token
        inboxService.deactivateDevice("cust-1", reg1.id());
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
