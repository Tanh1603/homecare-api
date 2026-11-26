package com.homecare.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
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

import com.homecare.household.client.BookingUsageGrpcClient;
import com.homecare.household.dto.HouseholdDtos.CreateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import com.homecare.household.entity.CareRecord;
import com.homecare.household.entity.MaintenanceSchedule;
import com.homecare.household.repository.CareRecordRepository;
import com.homecare.household.repository.HouseholdOutboxEventRepository;
import com.homecare.household.repository.MaintenancePolicyRepository;
import com.homecare.household.repository.MaintenanceScheduleRepository;
import com.homecare.household.service.HouseholdDomainService;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "homecare.maintenance.poll-ms=3600000",
        "homecare.locations.auto-seed-if-empty=false"
})
class DeviceCareHistoryPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired HouseholdDomainService service;
    @Autowired MaintenancePolicyRepository policyRepository;
    @Autowired MaintenanceScheduleRepository scheduleRepository;
    @Autowired CareRecordRepository careRecordRepository;
    @Autowired HouseholdOutboxEventRepository outboxEventRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean BookingUsageGrpcClient bookingUsageGrpcClient;

    @BeforeEach
    void setup() {
        when(bookingUsageGrpcClient.hasLinkedBookings(any())).thenReturn(false);
    }

    @Test
    void verifiesFlywaySeededPoliciesCheckConstraintsUniqueIndexAndSkipLockedConcurrency() throws Exception {
        // 1. Seeded baseline v0.1 category policies exist
        assertThat(policyRepository.findFirstByCategoryAndModelIsNullAndStatusOrderByPolicyVersionDesc("AIR_CONDITIONER", "APPROVED"))
                .isPresent();
        assertThat(policyRepository.findFirstByCategoryAndModelIsNullAndStatusOrderByPolicyVersionDesc("WATER_HEATER", "APPROVED"))
                .isPresent();

        String owner = "pg-it-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("PG Home", "123 Street", "Asia/Ho_Chi_Minh"));
        var device = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "Điều hòa", "AIR_CONDITIONER", "Daikin", null, null, LocalDate.now()));
        // Auto-created schedule from seeded category default policy
        assertThat(scheduleRepository.findByDeviceIdOrderByDueOnAsc(device.id())).hasSize(1);

        // 2. DB CHECK constraints reject invalid device status, origin_source, and cadence_months
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE devices SET status = 'INVALID' WHERE id = ?", device.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE devices SET origin_source = 'UNKNOWN' WHERE id = ?", device.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE maintenance_schedules SET cadence_months = 0 WHERE device_id = ?", device.id()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 3. uq_care_record_booking_kind enforces unique (booking_id, kind) while allowing multiple manual (booking_id NULL) records
        UUID bookingId = UUID.randomUUID();
        careRecordRepository.saveAndFlush(CareRecord.builder()
                .id(UUID.randomUUID()).deviceId(device.id()).ownerProfileId(owner).kind("PAID_REPAIR")
                .occurredAt(Instant.now()).title("First").bookingId(bookingId).createdAt(Instant.now()).build());
        assertThatThrownBy(() -> careRecordRepository.saveAndFlush(CareRecord.builder()
                .id(UUID.randomUUID()).deviceId(device.id()).ownerProfileId(owner).kind("PAID_REPAIR")
                .occurredAt(Instant.now()).title("Duplicate").bookingId(bookingId).createdAt(Instant.now()).build()))
                .isInstanceOf(DataIntegrityViolationException.class);

        careRecordRepository.saveAndFlush(CareRecord.builder()
                .id(UUID.randomUUID()).deviceId(device.id()).ownerProfileId(owner).kind("MANUAL")
                .occurredAt(Instant.now()).title("Manual 1").bookingId(null).createdAt(Instant.now()).build());
        careRecordRepository.saveAndFlush(CareRecord.builder()
                .id(UUID.randomUUID()).deviceId(device.id()).ownerProfileId(owner).kind("MANUAL")
                .occurredAt(Instant.now()).title("Manual 2").bookingId(null).createdAt(Instant.now()).build());

        // 4. Concurrent FOR UPDATE SKIP LOCKED reminder worker emits exactly 1 outbox event without rollback
        outboxEventRepository.deleteAll();
        scheduleRepository.deleteAll();
        LocalDate todayVn = LocalDate.now(HouseholdDomainService.VIETNAM_ZONE);
        scheduleRepository.saveAndFlush(MaintenanceSchedule.builder()
                .id(UUID.randomUUID()).deviceId(device.id()).ownerProfileId(owner).title("Due Now")
                .cadenceMonths(6).dueOn(todayVn.minusDays(1)).status("SCHEDULED").reminderEnabled(true)
                .createdAt(Instant.now()).build());

        var pool = Executors.newFixedThreadPool(2);
        var startGate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            startGate.await();
            return service.enqueueDueMaintenanceReminders(todayVn);
        });
        Future<Integer> f2 = pool.submit(() -> {
            startGate.await();
            return service.enqueueDueMaintenanceReminders(todayVn);
        });
        startGate.countDown();
        int totalEnqueued = f1.get() + f2.get();
        pool.shutdown();

        assertThat(totalEnqueued).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }
}
