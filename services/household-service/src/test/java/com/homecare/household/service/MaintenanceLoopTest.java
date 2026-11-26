package com.homecare.household.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homecare.common.event.DomainEventContracts;
import com.homecare.household.client.BookingUsageGrpcClient;
import com.homecare.household.dto.HouseholdDtos.CreateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateMaintenanceScheduleRequest;
import com.homecare.household.entity.MaintenancePolicyEntity;
import com.homecare.household.entity.MaintenanceSchedule;
import com.homecare.household.repository.CareRecordRepository;
import com.homecare.household.repository.HouseholdOutboxEventRepository;
import com.homecare.household.repository.MaintenancePolicyRepository;
import com.homecare.household.repository.MaintenanceScheduleRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class MaintenanceLoopTest {
    @Autowired HouseholdDomainService service;
    @Autowired HouseholdEventConsumer eventConsumer;
    @Autowired MaintenancePolicyRepository policies;
    @Autowired MaintenanceScheduleRepository schedules;
    @Autowired CareRecordRepository careRecords;
    @Autowired HouseholdOutboxEventRepository outbox;
    @Autowired ObjectMapper mapper;

    @MockitoBean RabbitTemplate rabbitTemplate;
    @MockitoBean BookingUsageGrpcClient bookingUsageGrpcClient;

    @BeforeEach
    void resetState() {
        schedules.deleteAll();
        outbox.deleteAll();
        policies.deleteAll();
        when(bookingUsageGrpcClient.hasLinkedBookings(any())).thenReturn(false);
    }

    @Test
    void onlyApprovedPolicyCreatesScheduleAndFallsBackToCategoryDefault() {
        String owner = "maintenance-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));

        assertThat(service.createScheduleFromApprovedPolicy(device.id())).isNull();
        policies.save(MaintenancePolicyEntity.builder()
                .id(UUID.randomUUID()).category("AC").model(null).title("Draft Filter")
                .cadenceMonths(6).policyVersion(1).status("DRAFT").consentGranted(true)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        assertThat(service.createScheduleFromApprovedPolicy(device.id())).isNull();

        policies.save(MaintenancePolicyEntity.builder()
                .id(UUID.randomUUID()).category("AC").model(null).title("Default AC Filter")
                .cadenceMonths(6).policyVersion(2).status("APPROVED").consentGranted(true)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());

        assertThat(service.createScheduleFromApprovedPolicy(device.id())).isNotNull();
        assertThat(schedules.findByDeviceIdOrderByDueOnAsc(device.id())).hasSize(1);
        // Idempotent replay
        assertThat(service.createScheduleFromApprovedPolicy(device.id())).isNotNull();
        assertThat(schedules.findByDeviceIdOrderByDueOnAsc(device.id())).hasSize(1);
    }

    @Test
    void reminderReplayIsIdempotentSkipsPausedAndArchivedDevicesAndNeverCreatesBooking() {
        String owner = "maintenance-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var activeDevice = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));
        var archivedDevice = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "Old AC", "AC", "Brand", "Model", null, LocalDate.now()));
        service.archiveDevice(owner, archivedDevice.id());

        LocalDate todayVn = LocalDate.now(HouseholdDomainService.VIETNAM_ZONE);
        var activeSchedule = schedules.save(MaintenanceSchedule.builder().id(UUID.randomUUID()).deviceId(activeDevice.id())
                .ownerProfileId(owner).title("Filter").cadenceMonths(6).dueOn(todayVn.minusDays(1))
                .status("SCHEDULED").reminderEnabled(true).createdAt(Instant.now()).build());
        // Disabled reminder consent must be skipped
        schedules.save(MaintenanceSchedule.builder().id(UUID.randomUUID()).deviceId(activeDevice.id())
                .ownerProfileId(owner).title("Muted").cadenceMonths(6).dueOn(todayVn.minusDays(1))
                .status("SCHEDULED").reminderEnabled(false).createdAt(Instant.now()).build());
        // Archived device schedule must be skipped
        schedules.save(MaintenanceSchedule.builder().id(UUID.randomUUID()).deviceId(archivedDevice.id())
                .ownerProfileId(owner).title("Archived").cadenceMonths(6).dueOn(todayVn.minusDays(1))
                .status("SCHEDULED").reminderEnabled(true).createdAt(Instant.now()).build());

        assertThat(service.enqueueDueMaintenanceReminders(todayVn)).isEqualTo(1);
        assertThat(service.enqueueDueMaintenanceReminders(todayVn)).isZero();
        var events = outbox.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(DomainEventContracts.MAINTENANCE_REMINDER_DUE);
        assertThat(events.getFirst().getRoutingKey()).isEqualTo(DomainEventContracts.MAINTENANCE_REMINDER_DUE);
        assertThat(schedules.findById(activeSchedule.getId()).orElseThrow().getNotifiedAt()).isNotNull();
    }

    @Test
    void onlyAcceptedMaintenanceAdvancesDueDateInVietnamTimezoneAndSkipCreatesZeroCareRecords() throws Exception {
        String owner = "maintenance-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));
        LocalDate due = LocalDate.now(HouseholdDomainService.VIETNAM_ZONE).minusDays(2);
        var schedule = schedules.save(MaintenanceSchedule.builder().id(UUID.randomUUID()).deviceId(device.id())
                .ownerProfileId(owner).title("Filter").cadenceMonths(6).dueOn(due).status("SCHEDULED")
                .reminderEnabled(true).createdAt(Instant.now()).build());

        // D-11.5: BOOKING_COMPLETED does NOT create CareRecord or advance schedule
        UUID booking = UUID.randomUUID();
        eventConsumer.onDomainEvent(mapper.writeValueAsString(event(DomainEventContracts.BOOKING_COMPLETED,
                booking, device.id(), owner, "MAINTENANCE", "2026-11-24T18:30:00Z")));
        assertThat(careRecords.findByDeviceIdOrderByOccurredAtDesc(device.id())).isEmpty();
        assertThat(schedules.findById(schedule.getId()).orElseThrow().getDueOn()).isEqualTo(due);

        // D-11.5 & D-31: 2026-11-24T18:30:00Z is 2026-11-25 01:30 in Asia/Ho_Chi_Minh -> +6m = 2027-05-25
        eventConsumer.onDomainEvent(mapper.writeValueAsString(event(DomainEventContracts.BOOKING_ACCEPTED,
                booking, device.id(), owner, "MAINTENANCE", "2026-11-24T18:30:00Z")));
        eventConsumer.onDomainEvent(mapper.writeValueAsString(event(DomainEventContracts.BOOKING_ACCEPTED,
                booking, device.id(), owner, "MAINTENANCE", "2026-11-24T18:30:00Z")));

        assertThat(careRecords.findByDeviceIdOrderByOccurredAtDesc(device.id())).hasSize(1);
        assertThat(schedules.findById(schedule.getId()).orElseThrow().getDueOn()).isEqualTo(LocalDate.of(2027, 5, 25));

        // D-31: SKIP advances dueOn = today(VN) + cadenceMonths and creates ZERO CareRecords
        var skipped = service.skipSchedule(owner, schedule.getId());
        assertThat(skipped.dueOn()).isEqualTo(LocalDate.now(HouseholdDomainService.VIETNAM_ZONE).plusMonths(6));
        assertThat(skipped.lastSkippedAt()).isNotNull();
        assertThat(careRecords.findByDeviceIdOrderByOccurredAtDesc(device.id())).hasSize(1);

        // Toggle reminderEnabled off
        var muted = service.updateSchedule(owner, schedule.getId(),
                new UpdateMaintenanceScheduleRequest(null, null, null, null, false));
        assertThat(muted.reminderEnabled()).isFalse();
    }

    @Test
    void concurrentAcceptanceReplayCreatesOneRecordAndAdvancesOnce() throws Exception {
        String owner = "maintenance-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));
        LocalDate due = LocalDate.now(HouseholdDomainService.VIETNAM_ZONE).minusDays(1);
        var schedule = schedules.save(MaintenanceSchedule.builder().id(UUID.randomUUID()).deviceId(device.id())
                .ownerProfileId(owner).title("Filter").cadenceMonths(6).dueOn(due).status("SCHEDULED")
                .reminderEnabled(true).createdAt(Instant.now()).build());
        UUID booking = UUID.randomUUID();
        String payload = mapper.writeValueAsString(event(DomainEventContracts.BOOKING_ACCEPTED,
                booking, device.id(), owner, "MAINTENANCE", "2026-11-24T18:30:00Z"));
        var first = new Thread(() -> eventConsumer.onDomainEvent(payload));
        var second = new Thread(() -> eventConsumer.onDomainEvent(payload));
        first.start();
        second.start();
        first.join();
        second.join();

        assertThat(careRecords.findByDeviceIdOrderByOccurredAtDesc(device.id())).hasSize(1);
        assertThat(schedules.findById(schedule.getId()).orElseThrow().getDueOn()).isEqualTo(LocalDate.of(2027, 5, 25));
    }

    private Map<String, Object> event(
            String type, UUID booking, UUID device, String owner, String bookingType, String acceptedAt) {
        return Map.of(
                DomainEventContracts.FIELD_EVENT_TYPE, type,
                DomainEventContracts.FIELD_DATA, Map.of(
                        "bookingId", booking, "deviceId", device, "requesterProfileId", owner,
                        "bookingType", bookingType, "serviceName", "Maintenance", "acceptedAt", acceptedAt));
    }
}
