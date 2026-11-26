package com.homecare.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.DisputeDtos.OpenDisputeRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.service.BookingAcceptanceService;
import com.homecare.booking.service.BookingAcceptanceTimerWorker;
import com.homecare.booking.service.DisputeService;
import com.homecare.booking.service.WorkEvidenceStoragePort;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;

/**
 * Verifies Feature 4 on real PostgreSQL 16 with spring.flyway.enabled=true + ddl-auto=validate
 * using V20261119_00 baseline through V20261126_02 (SC-004, Constitution Principle VII).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "homecare.booking.acceptance-timer-poll-ms=3600000",
        "homecare.dispute.sla-poll-ms=3600000"
})
class ExecutionAcceptanceDisputePostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired BookingRepository bookingRepository;
    @Autowired BookingAcceptanceService acceptanceService;
    @Autowired BookingAcceptanceTimerWorker timerWorker;
    @Autowired DisputeService disputeService;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean HouseholdVerificationClient householdClient;
    @MockitoBean ProviderGrpcClientAdapter providerGrpcClient;
    @MockitoBean WorkEvidenceStoragePort storagePort;

    @Test
    void validatesFullFlywayMigrationsConstraintsAndSkipLockedTimersOnPostgres16() {
        String customer = "pg-f4-" + UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Instant now = Instant.now();

        BookingEntity booking = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customer)
                .serviceId(UUID.randomUUID())
                .serviceName("Bảo dưỡng điều hòa")
                .providerId(providerId)
                .preferredStart(now.minus(1, ChronoUnit.HOURS))
                .preferredEnd(now.plus(1, ChronoUnit.HOURS))
                .address("12 Hai Bà Trưng")
                .status(BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name())
                .allocationStatus("COMPLETED")
                .quotedPrice(350_000L)
                .completionSubmittedAt(now.minus(8, ChronoUnit.DAYS))
                .completionSummary("Đã hoàn tất")
                .createdAt(now.minus(9, ChronoUnit.DAYS))
                .updatedAt(now.minus(8, ChronoUnit.DAYS))
                .build());

        // DB CHECK constraint rejects invalid acceptance_source & revision_count > 2
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE bookings SET acceptance_source = 'INVALID' WHERE id = ?", booking.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE bookings SET revision_count = 3 WHERE id = ?", booking.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Notification delivered 8 days ago -> SKIP LOCKED auto-accept transitions to ACCEPTED (SYSTEM_TIMEOUT)
        acceptanceService.recordCompletionNotificationDelivered(booking.getId(), now.minus(8, ChronoUnit.DAYS));
        var summary = timerWorker.processTimers(now);
        assertThat(summary.autoAccepted()).isGreaterThanOrEqualTo(1);
        BookingEntity accepted = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(accepted.getStatus()).isEqualTo(BookingStatus.ACCEPTED.name());
        assertThat(accepted.getAcceptanceSource()).isEqualTo("SYSTEM_TIMEOUT");

        // Active dispute uniqueness enforced on Postgres (uq_disputes_active_per_booking)
        ActorContext custActor = new ActorContext(customer, AccessMode.PERSONAL, null,
                Set.of("CUSTOMER"), Set.of(), Set.of(), Set.of());
        var dispute = disputeService.openDispute(
                custActor, booking.getId(),
                new OpenDisputeRequest("QUALITY_ISSUE", "Máy lạnh chưa mát sâu", List.of()));
        assertThat(dispute.openedStage()).isEqualTo("POST_ACCEPTANCE");
        assertThatThrownBy(() -> disputeService.openDispute(
                custActor, booking.getId(),
                new OpenDisputeRequest("QUALITY_ISSUE", "Duplicate", List.of())))
                .isInstanceOf(RuntimeException.class);
    }
}
