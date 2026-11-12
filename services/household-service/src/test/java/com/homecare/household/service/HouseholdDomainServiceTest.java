package com.homecare.household.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.common.error.DomainException;
import com.homecare.common.web.EtagSupport;
import com.homecare.household.dto.HouseholdDtos.CareRecordResponse;
import com.homecare.household.dto.HouseholdDtos.CreateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.CreateMaintenanceScheduleRequest;
import com.homecare.household.dto.HouseholdDtos.CreateWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.DeviceResponse;
import com.homecare.household.dto.HouseholdDtos.HouseholdResponse;
import com.homecare.household.dto.HouseholdDtos.MaintenanceScheduleResponse;
import com.homecare.household.dto.HouseholdDtos.UpdateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateMaintenanceScheduleRequest;
import com.homecare.household.dto.HouseholdDtos.VerifyDeviceReferenceResponse;
import com.homecare.household.dto.HouseholdDtos.VerifyWarrantyResponse;
import com.homecare.household.dto.HouseholdDtos.WarrantyResponse;
import com.homecare.household.entity.HouseholdOutboxEvent;
import com.homecare.household.repository.HouseholdOutboxEventRepository;

@SpringBootTest
@ActiveProfiles("test")
class HouseholdDomainServiceTest {

    @Autowired
    private HouseholdDomainService service;

    @Autowired
    private HouseholdEventConsumer eventConsumer;

    @Autowired
    private HouseholdOutboxEventRepository outboxEventRepository;

    @Test
    void enforcesOwnershipEtagWarrantyDateOrderScheduleNotificationConflictAndOutbox() {
        String ownerA = "kc-user-customer-a";
        String ownerB = "kc-user-customer-b";

        HouseholdResponse household = service.createHousehold(
                ownerA,
                new CreateHouseholdRequest("Nhà Quận 7", "12 Nguyễn Hữu Thọ", "Asia/Ho_Chi_Minh"));

        assertThatThrownBy(() -> service.getHousehold(ownerB, household.id()))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        DeviceResponse device = service.createDevice(
                ownerA,
                new CreateDeviceRequest(
                        household.id(),
                        "Máy lạnh phòng khách",
                        "AC",
                        "Daikin",
                        "FTKC25UAVMV",
                        "SN-1001",
                        LocalDate.of(2025, 1, 15)));

        assertThatThrownBy(() -> service.updateDevice(
                ownerA,
                device.id(),
                "\"v99\"",
                new UpdateDeviceRequest("Tên mới", null, null, null, null, null, null)))
                .isInstanceOf(EtagSupport.PreconditionException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_VERSION_MISMATCH");

        DeviceResponse updatedDevice = service.updateDevice(
                ownerA,
                device.id(),
                EtagSupport.format(device.version()),
                new UpdateDeviceRequest("Máy lạnh Inverter", null, null, null, null, null, null));
        assertThat(updatedDevice.name()).isEqualTo("Máy lạnh Inverter");

        assertThatThrownBy(() -> service.createWarranty(
                ownerA,
                device.id(),
                new CreateWarrantyRequest(
                        "Daikin VN",
                        LocalDate.of(2026, 5, 10),
                        LocalDate.of(2026, 5, 1),
                        "Invalid dates",
                        null)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("DATE_ORDER_INVALID");

        VerifyDeviceReferenceResponse verified = service.verifyDeviceReference(device.id(), ownerA);
        assertThat(verified.valid()).isTrue();
        assertThat(verified.deviceType()).isEqualTo("AC");

        VerifyDeviceReferenceResponse forbiddenVerify = service.verifyDeviceReference(device.id(), ownerB);
        assertThat(forbiddenVerify.valid()).isFalse();

        MaintenanceScheduleResponse schedule = service.createSchedule(
                ownerA,
                device.id(),
                new CreateMaintenanceScheduleRequest(
                        "Vệ sinh lưới lọc định kỳ",
                        6,
                        LocalDate.now().minusDays(1)));

        int count = service.enqueueDueMaintenanceReminders(LocalDate.now());
        assertThat(count).isGreaterThanOrEqualTo(1);

        List<HouseholdOutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).isNotEmpty();
        assertThat(outboxEvents.getFirst().getEventType()).isEqualTo("maintenance.reminder.due.v1");

        assertThatThrownBy(() -> service.updateSchedule(
                ownerA,
                schedule.id(),
                new UpdateMaintenanceScheduleRequest(null, null, LocalDate.now().plusDays(15), null)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("SCHEDULE_ALREADY_NOTIFIED");
    }

    @Test
    void autoActivatesWarrantyFromDeliveredOrderLineVerifiesEligibilityAndRecordsWarrantyCareHistory() {
        String buyer = "kc-buyer-warranty-1";
        UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID orderId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        String deliveredEventJson = """
                {"eventId":"%s","eventType":"order.line.delivered.v1","data":{"orderId":"%s","orderLineId":"%s","buyerProfileId":"%s","organizationId":"%s","merchantName":"Điện Lạnh Bách Khoa Sài Gòn","productId":"%s","productName":"Màng lọc RO DOW Filmtec 100GPD","category":"WATER_PURIFIER","brand":"Filmtec","productType":"MAJOR_PART","warrantyMonths":12,"returnWindowDays":15}}
                """.formatted(UUID.randomUUID(), orderId, orderLineId, buyer, orgId, productId).strip();

        eventConsumer.onDomainEvent(deliveredEventJson);
        eventConsumer.onDomainEvent(deliveredEventJson); // idempotent duplicate test

        List<DeviceResponse> devices = service.listDevices(buyer, null);
        assertThat(devices).hasSize(1);
        DeviceResponse autoDevice = devices.getFirst();
        assertThat(autoDevice.deviceType()).isEqualTo("WATER_PURIFIER");

        List<WarrantyResponse> warranties = service.listWarranties(buyer, autoDevice.id());
        assertThat(warranties).hasSize(1);
        WarrantyResponse autoWarranty = warranties.getFirst();
        assertThat(autoWarranty.source()).isEqualTo("ORDER_AUTO_ACTIVATED");
        assertThat(autoWarranty.organizationId()).isEqualTo(orgId);
        assertThat(autoWarranty.status()).isEqualTo("ACTIVE");

        VerifyWarrantyResponse verifyOk = service.verifyWarrantyEligibility(autoWarranty.id(), buyer);
        assertThat(verifyOk.valid()).isTrue();
        assertThat(verifyOk.expired()).isFalse();
        assertThat(verifyOk.organizationId()).isEqualTo(orgId);

        UUID bookingId = UUID.randomUUID();
        String completedBookingJson = """
                {"eventId":"%s","eventType":"booking.completed.v1","data":{"bookingId":"%s","deviceId":"%s","requesterProfileId":"%s","bookingType":"WARRANTY_CLAIM","serviceName":"Bảo hành Màng lọc RO"}}
                """.formatted(UUID.randomUUID(), bookingId, autoDevice.id(), buyer).strip();

        eventConsumer.onDomainEvent(completedBookingJson);

        List<CareRecordResponse> careRecords = service.listCareRecords(buyer, autoDevice.id());
        assertThat(careRecords).hasSize(1);
        assertThat(careRecords.getFirst().kind()).isEqualTo("WARRANTY_REPAIR");
        assertThat(careRecords.getFirst().bookingId()).isEqualTo(bookingId);
    }
}
