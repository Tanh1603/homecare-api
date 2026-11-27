package com.homecare.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.common.event.DomainEventContracts;
import com.homecare.notification.dto.NotificationDtos.NotificationResponse;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class LifecycleNotificationConsumerTest {

    @Autowired
    private DomainEventConsumer consumer;

    @Autowired
    private NotificationInboxService inboxService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void consumesLifecycleEventsWithVietnameseCopyDeeplinksAndEventDeduplication() throws Exception {
        String recipient = "lifecycle-" + UUID.randomUUID();
        String deviceId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();

        String[] events = {
                event("evt-warranty", "WarrantyActivated", """
                        "ownerProfileId":"%s","deviceId":"%s"
                        """.formatted(recipient, deviceId)),
                event("evt-installation", "InstallationCompleted", """
                        "requesterProfileId":"%s","deviceId":"%s"
                        """.formatted(recipient, deviceId)),
                event("evt-reminder", "maintenance.reminder.due.v1", """
                        "ownerProfileId":"%s","deviceId":"%s","title":"Máy lạnh"
                        """.formatted(recipient, deviceId)),
                event("evt-booking", "booking.completed.v1", """
                        "requesterProfileId":"%s","bookingId":"%s","serviceName":"Bảo dưỡng"
                        """.formatted(recipient, requestId)),
                event("evt-care", "CareRecordUpdated", """
                        "ownerProfileId":"%s","deviceId":"%s"
                        """.formatted(recipient, deviceId)),
                event("evt-quote", "QuotationSent", """
                        "requesterProfileId":"%s","requestId":"%s"
                        """.formatted(recipient, requestId)),
                event("evt-rejected", "WarrantyRequestRejected", """
                        "requesterProfileId":"%s","requestId":"%s"
                        """.formatted(recipient, requestId)),
                event("evt-fallback", "FallbackToMarketplaceAvailable", """
                        "requesterProfileId":"%s","requestId":"%s"
                        """.formatted(recipient, requestId))
        };

        for (String event : events) {
            consumer.onDomainEvent(event);
        }
        consumer.onDomainEvent(events[0]);

        var page = inboxService.inboxPage(recipient, 1, 20);
        assertThat(page.items()).hasSize(8);
        assertThat(page.items()).allSatisfy(notification ->
                assertThat(notification.title()).isNotBlank());
        assertThat(page.items()).anySatisfy(notification ->
                assertThat(notification.title()).contains("Bảo hành"));
        assertThat(page.items()).anySatisfy(notification ->
                assertThat(notification.body()).contains("homecare://devices/" + deviceId));
        assertThat(page.items()).anySatisfy(notification ->
                assertThat(notification.body()).contains("homecare://requests/" + requestId));

        NotificationResponse first = page.items().getFirst();
        assertThat(objectMapper.writeValueAsString(first)).contains("deeplink");
    }

    @Test
    void ignoresUnknownLifecycleEventWithoutCreatingNotification() {
        String recipient = "unknown-" + UUID.randomUUID();
        consumer.onDomainEvent(event("evt-unknown", "UnknownLifecycleEvent", """
                "requesterProfileId":"%s"
                """.formatted(recipient)));

        assertThat(inboxService.inboxPage(recipient, 1, 20).items()).isEmpty();
    }

    @Test
    void handlesBookingAcceptedAndRethrowsCompletionCallbackPublishFailure() {
        String recipient = "accepted-" + UUID.randomUUID();
        String bookingId = UUID.randomUUID().toString();
        consumer.onDomainEvent(event("evt-accepted", DomainEventContracts.BOOKING_ACCEPTED, """
                "requesterProfileId":"%s","bookingId":"%s","serviceName":"Sửa điều hòa"
                """.formatted(recipient, bookingId)));
        assertThat(inboxService.inboxPage(recipient, 1, 20).items()).hasSize(1);

        RabbitTemplate failingRabbit = mock(RabbitTemplate.class);
        doThrow(new RuntimeException("broker down"))
                .when(failingRabbit).convertAndSend(anyString(), anyString(), anyString());
        @SuppressWarnings("unchecked")
        ObjectProvider<RabbitTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(failingRabbit);
        DomainEventConsumer isolated = new DomainEventConsumer(inboxService, objectMapper, provider);

        String pendingPayload = event("evt-pending-fail", DomainEventContracts.BOOKING_COMPLETION_PENDING_ACCEPTANCE, """
                "requesterProfileId":"%s","bookingId":"%s","serviceName":"Sửa điều hòa"
                """.formatted(recipient, bookingId));
        assertThatThrownBy(() -> isolated.onDomainEvent(pendingPayload))
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("completion delivery callback")
                .hasRootCauseMessage("broker down");
    }

    private String event(String id, String type, String data) {
        return """
                {"eventId":"%s","eventType":"%s","data":{%s}}
                """.formatted(id, type, data);
    }
}
