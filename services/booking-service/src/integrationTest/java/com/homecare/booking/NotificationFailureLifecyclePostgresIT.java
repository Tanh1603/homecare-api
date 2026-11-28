package com.homecare.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingExecutionDtos.AcceptBookingRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.repository.BookingHistoryRepository;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.DisputeRepository;
import com.homecare.booking.service.BookingAcceptanceService;
import com.homecare.booking.service.BookingAcceptanceTimerWorker;
import com.homecare.booking.service.WorkEvidenceStoragePort;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.web.EtagSupport;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "homecare.booking.acceptance-timer-poll-ms=3600000",
        "homecare.dispute.sla-poll-ms=3600000"
})
class NotificationFailureLifecyclePostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired BookingRepository bookingRepository;
    @Autowired BookingHistoryRepository historyRepository;
    @Autowired BookingOutboxEventRepository outboxRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired BookingAcceptanceService acceptanceService;
    @Autowired BookingAcceptanceTimerWorker timerWorker;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean HouseholdVerificationClient householdClient;
    @MockitoBean ProviderGrpcClientAdapter providerGrpcClient;
    @MockitoBean WorkEvidenceStoragePort storagePort;

    @Test
    void detects24hUnnotifiedBookingsIndependentlyAndClearsOnlyOnConfirmedDeliveryOrExplicitAccept() {
        String customer = "pg-d27-" + UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        BookingEntity overdueBooking = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customer)
                .serviceId(UUID.randomUUID())
                .serviceName("Sua may giat")
                .providerId(providerId)
                .preferredStart(now.minus(26, ChronoUnit.HOURS))
                .preferredEnd(now.minus(25, ChronoUnit.HOURS))
                .address("18 Le Loi")
                .status(BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name())
                .allocationStatus("COMPLETED")
                .quotedPrice(400_000L)
                .completionSubmittedAt(now.minus(25, ChronoUnit.HOURS))
                .completionSummary("Da thay van cap nuoc")
                .createdAt(now.minus(27, ChronoUnit.HOURS))
                .updatedAt(now.minus(25, ChronoUnit.HOURS))
                .build());

        // 1. Independent 24h worker detection flags notificationFailed=true without auto-accept/cancel/Dispute
        var run1 = timerWorker.processTimers(now);
        assertThat(run1.overdueNotificationFailures()).isGreaterThanOrEqualTo(1);
        assertThat(run1.autoAccepted()).isZero();

        BookingEntity flagged = bookingRepository.findById(overdueBooking.getId()).orElseThrow();
        assertThat(flagged.getStatus()).isEqualTo(BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name());
        assertThat(flagged.isNotificationFailed()).isTrue();
        assertThat(flagged.getNotificationFailedAt()).isNotNull();
        assertThat(flagged.getAutoAcceptAt()).isNull();
        assertThat(flagged.isAdminReviewFlagged()).isTrue();
        assertThat(flagged.getAdminReviewReason()).startsWith("NOTIFICATION_FAILED");
        assertThat(disputeRepository.findByBookingIdOrderByCreatedAtDesc(overdueBooking.getId())).isEmpty();

        // 2. Repeated worker poll must NOT duplicate Admin alerts or history entries
        long historyCountAfterFirstFlag = historyRepository.findByBookingIdOrderByOccurredAtAsc(overdueBooking.getId()).size();
        var run2 = timerWorker.processTimers(now.plusSeconds(120));
        assertThat(run2.overdueNotificationFailures()).isZero();
        assertThat(historyRepository.findByBookingIdOrderByOccurredAtAsc(overdueBooking.getId()))
                .hasSize((int) historyCountAfterFirstFlag);

        // 3. Admin list/detail/retry keeps notificationFailed=true until delivered.v1 arrives
        var adminList = acceptanceService.listNotificationFailedBookings(1, 20);
        assertThat(adminList.items()).extracting("bookingId").contains(overdueBooking.getId());

        ActorContext nonAdmin = new ActorContext(customer, AccessMode.PERSONAL, null,
                Set.of("CUSTOMER"), Set.of(), Set.of(), Set.of());
        assertThatThrownBy(() -> acceptanceService.retryCompletionNotification(
                nonAdmin, overdueBooking.getId(), EtagSupport.format(flagged.getVersion()), "Retry"))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        ActorContext admin = new ActorContext("admin-1", AccessMode.PERSONAL, null,
                Set.of("ADMIN"), Set.of(), Set.of(), Set.of());
        var retried = acceptanceService.retryCompletionNotification(
                admin, overdueBooking.getId(), EtagSupport.format(flagged.getVersion()), "Thu gui lai FCM");
        assertThat(retried.notificationRetryCount()).isEqualTo(1);
        assertThat(retried.lastNotificationRetryAt()).isNotNull();

        BookingEntity stillFailedUntilCallback = bookingRepository.findById(overdueBooking.getId()).orElseThrow();
        assertThat(stillFailedUntilCallback.isNotificationFailed()).isTrue();
        assertThat(stillFailedUntilCallback.getCompletionNotifiedAt()).isNull();
        assertThat(outboxRepository.findAll().stream()
                .filter(e -> overdueBooking.getId().equals(e.getAggregateId())
                        && DomainEventContracts.BOOKING_COMPLETION_PENDING_ACCEPTANCE.equals(e.getEventType()))
                .toList())
                .extracting(BookingOutboxEventEntity::getEventType)
                .containsExactly(DomainEventContracts.BOOKING_COMPLETION_PENDING_ACCEPTANCE);

        // 4. Delivery confirmation clears exception and starts Day 7 timer
        Instant deliveredAt = Instant.parse("2026-11-28T03:00:00Z"); // 10:00 +07:00
        acceptanceService.recordCompletionNotificationDelivered(overdueBooking.getId(), deliveredAt);
        BookingEntity delivered = bookingRepository.findById(overdueBooking.getId()).orElseThrow();
        assertThat(delivered.isNotificationFailed()).isFalse();
        assertThat(delivered.getNotificationFailedAt()).isNull();
        assertThat(delivered.isAdminReviewFlagged()).isFalse();
        assertThat(delivered.getCompletionNotifiedAt()).isEqualTo(deliveredAt);
        assertThat(delivered.getAutoAcceptAt()).isEqualTo(BookingAcceptanceService.computeAutoAcceptAt(deliveredAt));

        // 5. Stale failed.v1 after delivered.v1 is ignored
        acceptanceService.recordCompletionNotificationFailed(overdueBooking.getId(), "Late DLQ callback");
        BookingEntity afterStaleFail = bookingRepository.findById(overdueBooking.getId()).orElseThrow();
        assertThat(afterStaleFail.isNotificationFailed()).isFalse();
        assertThat(afterStaleFail.getAutoAcceptAt()).isEqualTo(BookingAcceptanceService.computeAutoAcceptAt(deliveredAt));

        // 6. Explicit customer acceptance while notificationFailed=true clears flag cleanly
        BookingEntity explicitAcceptBooking = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customer)
                .serviceId(UUID.randomUUID())
                .serviceName("Ve sinh may lanh")
                .providerId(providerId)
                .preferredStart(now.minus(30, ChronoUnit.HOURS))
                .preferredEnd(now.minus(29, ChronoUnit.HOURS))
                .address("20 Le Loi")
                .status(BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name())
                .allocationStatus("COMPLETED")
                .quotedPrice(250_000L)
                .completionSubmittedAt(now.minus(26, ChronoUnit.HOURS))
                .completionSummary("Xong")
                .notificationFailed(true)
                .notificationFailedAt(now.minus(2, ChronoUnit.HOURS))
                .adminReviewFlagged(true)
                .adminReviewReason("NOTIFICATION_FAILED: DLQ")
                .createdAt(now.minus(31, ChronoUnit.HOURS))
                .updatedAt(now.minus(2, ChronoUnit.HOURS))
                .build());

        var accepted = acceptanceService.acceptBooking(
                nonAdmin,
                explicitAcceptBooking.getId(),
                EtagSupport.format(explicitAcceptBooking.getVersion()),
                new AcceptBookingRequest("Da kiem tra xong"));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        BookingEntity clearedAfterExplicit = bookingRepository.findById(explicitAcceptBooking.getId()).orElseThrow();
        assertThat(clearedAfterExplicit.isNotificationFailed()).isFalse();
        assertThat(clearedAfterExplicit.isAdminReviewFlagged()).isFalse();
    }
}
