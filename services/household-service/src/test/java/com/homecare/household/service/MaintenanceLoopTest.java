package com.homecare.household.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homecare.common.event.DomainEventContracts;
import com.homecare.household.dto.HouseholdDtos.CreateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import com.homecare.household.entity.MaintenancePolicyEntity;
import com.homecare.household.entity.MaintenanceSchedule;
import com.homecare.household.repository.CareRecordRepository;
import com.homecare.household.repository.MaintenancePolicyRepository;
import com.homecare.household.repository.MaintenanceScheduleRepository;
import com.homecare.household.repository.HouseholdOutboxEventRepository;

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

    @Test
    void onlyApprovedConsentedPolicyCreatesSchedule() {
        String owner = "maintenance-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));

        assertThat(service.createScheduleFromApprovedPolicy(device.id())).isNull();
        policies.save(MaintenancePolicyEntity.builder()
                .id(UUID.randomUUID()).deviceId(device.id()).ownerProfileId(owner).title("Filter")
                .category("AC").model("Model")
                .cadenceMonths(6).policyVersion(1).status("APPROVED").consentGranted(false)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        assertThat(service.createScheduleFromApprovedPolicy(device.id())).isNull();
        policies.deleteAll();
        policies.save(MaintenancePolicyEntity.builder()
                .id(UUID.randomUUID()).deviceId(device.id()).ownerProfileId(owner).title("Filter")
                .category("AC").model("Model")
                .cadenceMonths(6).policyVersion(1).status("APPROVED").consentGranted(true)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());

        assertThat(service.createScheduleFromApprovedPolicy(device.id())).isNotNull();
        assertThat(schedules.findByDeviceIdOrderByDueOnAsc(device.id())).hasSize(1);
        assertThat(service.createScheduleFromApprovedPolicy(device.id())).isNotNull();
        assertThat(schedules.findByDeviceIdOrderByDueOnAsc(device.id())).hasSize(1);
    }

    @Test
    void policyCategoryAndModelMustMatchDevice() {
        String owner = "maintenance-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));
        service.createMaintenancePolicy(MaintenancePolicyEntity.builder().id(UUID.randomUUID())
                .ownerProfileId(owner).title("Wrong").category("WASHER").model("Other").cadenceMonths(6)
                .policyVersion(1).status("APPROVED").consentGranted(true)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        assertThat(schedules.findByDeviceIdOrderByDueOnAsc(device.id())).isEmpty();
    }

    @Test
    void latePolicyBackfillsMatchingExistingDevices() {
        String owner = "maintenance-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));
        service.createMaintenancePolicy(MaintenancePolicyEntity.builder().id(UUID.randomUUID())
                .ownerProfileId(owner).title("Filter").category("AC").model("Model").cadenceMonths(6)
                .policyVersion(2).status("APPROVED").consentGranted(true)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        assertThat(schedules.findByDeviceIdOrderByDueOnAsc(device.id())).hasSize(1);
    }

    @Test
    void reminderReplayIsIdempotentAndDoesNotCreateBookingOrOrder() {
        String owner = "maintenance-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));
        var schedule = schedules.save(MaintenanceSchedule.builder().id(UUID.randomUUID()).deviceId(device.id())
                .ownerProfileId(owner).title("Filter").cadenceMonths(6).dueOn(LocalDate.now().minusDays(1))
                .status("SCHEDULED").createdAt(Instant.now()).build());

        assertThat(service.enqueueDueMaintenanceReminders(LocalDate.now())).isEqualTo(1);
        assertThat(service.enqueueDueMaintenanceReminders(LocalDate.now())).isZero();
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(schedules.findById(schedule.getId()).orElseThrow().getNotifiedAt()).isNotNull();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void onlyCompletedMaintenanceAdvancesDueDateAndRecordsOnce() throws Exception {
        String owner = "maintenance-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));
        LocalDate due = LocalDate.now().minusDays(1);
        var schedule = schedules.save(MaintenanceSchedule.builder().id(UUID.randomUUID()).deviceId(device.id())
                .ownerProfileId(owner).title("Filter").cadenceMonths(6).dueOn(due).status("SCHEDULED")
                .createdAt(Instant.now()).build());
        UUID booking = UUID.randomUUID();
        eventConsumer.onDomainEvent(mapper.writeValueAsString(event(DomainEventContracts.BOOKING_COMPLETED,
                booking, device.id(), owner, "MAINTENANCE")));
        eventConsumer.onDomainEvent(mapper.writeValueAsString(event(DomainEventContracts.BOOKING_COMPLETED,
                booking, device.id(), owner, "MAINTENANCE")));

        assertThat(careRecords.findByDeviceIdOrderByOccurredAtDesc(device.id())).hasSize(1);
        assertThat(schedules.findById(schedule.getId()).orElseThrow().getDueOn()).isEqualTo(due.plusMonths(6));
        for (String type : new String[] {"BOOKING_CANCELLED", "BOOKING_NO_SHOW", "BOOKING_DISPUTED"}) {
            eventConsumer.onDomainEvent(mapper.writeValueAsString(event(type, UUID.randomUUID(), device.id(), owner, "MAINTENANCE")));
        }
        assertThat(schedules.findById(schedule.getId()).orElseThrow().getDueOn()).isEqualTo(due.plusMonths(6));
    }

    @Test
    void concurrentCompletionReplayCreatesOneRecordAndAdvancesOnce() throws Exception {
        String owner = "maintenance-" + UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner,
                new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));
        LocalDate due = LocalDate.now().minusDays(1);
        var schedule = schedules.save(MaintenanceSchedule.builder().id(UUID.randomUUID()).deviceId(device.id())
                .ownerProfileId(owner).title("Filter").cadenceMonths(6).dueOn(due).status("SCHEDULED")
                .createdAt(Instant.now()).build());
        UUID booking = UUID.randomUUID();
        String payload = mapper.writeValueAsString(event(DomainEventContracts.BOOKING_COMPLETED,
                booking, device.id(), owner, "MAINTENANCE"));
        var first = new Thread(() -> eventConsumer.onDomainEvent(payload));
        var second = new Thread(() -> eventConsumer.onDomainEvent(payload));
        first.start();
        second.start();
        first.join();
        second.join();

        assertThat(careRecords.findByDeviceIdOrderByOccurredAtDesc(device.id())).hasSize(1);
        assertThat(schedules.findById(schedule.getId()).orElseThrow().getDueOn()).isEqualTo(due.plusMonths(6));
    }

    private java.util.Map<String, Object> event(String type, UUID booking, UUID device, String owner, String bookingType) {
        return java.util.Map.of(
                DomainEventContracts.FIELD_EVENT_TYPE, type,
                DomainEventContracts.FIELD_DATA, java.util.Map.of(
                        "bookingId", booking, "deviceId", device, "requesterProfileId", owner,
                        "bookingType", bookingType, "serviceName", "Maintenance"));
    }
}
