package com.homecare.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.homecare.common.event.DomainEventContracts;
import com.homecare.notification.dto.NotificationDtos.NotificationResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class ScheduleNotificationConsumerTest {

    @Autowired
    private DomainEventConsumer consumer;

    @Autowired
    private NotificationInboxService inboxService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void processesScheduleUpdatedRequestCreatedAndReviewedEvents() throws Exception {
        String techProfileId = "tech-notif-" + UUID.randomUUID();
        String ownerProfileId = "owner-notif-" + UUID.randomUUID();
        String adminProfileId = "admin-notif-" + UUID.randomUUID();

        String updatedPayload = objectMapper.writeValueAsString(Map.of(
                DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString(),
                DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.PROVIDER_SCHEDULE_UPDATED,
                DomainEventContracts.FIELD_DATA, Map.of(
                        "technicianId", UUID.randomUUID().toString(),
                        "technicianProfileId", techProfileId,
                        "summary", "Tổ chức vừa cập nhật lịch tuần của bạn.")));
        consumer.onDomainEvent(updatedPayload);

        String reqCreatedPayload = objectMapper.writeValueAsString(Map.of(
                DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString(),
                DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.PROVIDER_SCHEDULE_REQUEST_CREATED,
                DomainEventContracts.FIELD_DATA, Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "technicianName", "Nguyễn Văn A",
                        "requestType", "LEAVE",
                        "fromDate", "2026-12-28",
                        "toDate", "2026-12-28",
                        "recipientProfileIds", List.of(ownerProfileId, adminProfileId))));
        consumer.onDomainEvent(reqCreatedPayload);

        String reqReviewedPayload = objectMapper.writeValueAsString(Map.of(
                DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString(),
                DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.PROVIDER_SCHEDULE_REQUEST_REVIEWED,
                DomainEventContracts.FIELD_DATA, Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "technicianProfileId", techProfileId,
                        "requestType", "LEAVE",
                        "fromDate", "2026-12-28",
                        "toDate", "2026-12-28",
                        "status", "APPROVED",
                        "reviewNote", "Đồng ý nghỉ")));
        consumer.onDomainEvent(reqReviewedPayload);

        List<NotificationResponse> techNotifs = inboxService.inboxPage(techProfileId, 1, 20).items();
        assertThat(techNotifs).hasSize(2);
        assertThat(techNotifs).extracting(NotificationResponse::eventType)
                .containsExactlyInAnyOrder(
                        DomainEventContracts.PROVIDER_SCHEDULE_UPDATED,
                        DomainEventContracts.PROVIDER_SCHEDULE_REQUEST_REVIEWED);

        assertThat(inboxService.inboxPage(ownerProfileId, 1, 20).items()).hasSize(1);
        assertThat(inboxService.inboxPage(adminProfileId, 1, 20).items()).hasSize(1);
    }
}
