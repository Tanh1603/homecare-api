package com.homecare.household.service;

import com.homecare.household.repository.HouseholdOutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.VerifiedMembership;
import com.homecare.common.web.EtagSupport;
import com.homecare.household.client.ProviderOrgClient;
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
import com.homecare.household.dto.HouseholdDtos.UpdateWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.VerifyDeviceReferenceResponse;
import com.homecare.household.dto.HouseholdDtos.VerifyWarrantyResponse;
import com.homecare.household.dto.HouseholdDtos.WarrantyResponse;
import com.homecare.household.entity.HouseholdOutboxEvent;
import com.homecare.household.repository.DeviceRepository;
import com.homecare.household.repository.MaintenanceScheduleRepository;
import com.homecare.household.repository.WarrantyRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class HouseholdDomainServiceTest {
    @Autowired WebApplicationContext webContext;
    @Autowired WarrantyRepository warranties;
    @Autowired MaintenanceScheduleRepository schedules;
    @Autowired DeviceRepository devices;

    @Test
    void customerListsAndHouseholdDeleteAreOwnerScopedAndCascade() throws Exception {
        String owner = UUID.randomUUID().toString();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner, new CreateDeviceRequest(household.id(), "AC", "AC", "Brand", "Model", null, LocalDate.now()));
        var warranty = service.createWarranty(owner, device.id(), new CreateWarrantyRequest("Vendor", LocalDate.now(), LocalDate.now().plusYears(1), null, null));
        service.createSchedule(owner, device.id(), new CreateMaintenanceScheduleRequest("Maintenance", 1, LocalDate.now().plusDays(1)));
        var mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        var auth = SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(owner));
        for (String path : List.of("/api/v1/warranties", "/api/v1/maintenance-schedules")) {
            mvc.perform(MockMvcRequestBuilders.get(path).with(auth))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.data.items.length()").value(1));
        }
        assertThatThrownBy(() -> service.deleteHousehold("outsider", household.id())).isInstanceOf(DomainException.class);
        service.deleteHousehold(owner, household.id());
        assertThat(devices.findById(device.id())).isEmpty();
        assertThat(warranties.findById(warranty.id())).isEmpty();
        assertThat(schedules.findByDeviceIdOrderByDueOnAsc(device.id())).isEmpty();
    }

    @Autowired
    private HouseholdDomainService service;

    @Autowired
    private HouseholdEventConsumer eventConsumer;

    @Autowired
    private HouseholdOutboxEventRepository outboxEventRepository;

    @MockitoBean
    private ProviderOrgClient providerOrgClient;

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
        UUID expectedDetId = UUID.nameUUIDFromBytes(
                ("maint-due-" + schedule.id() + "-" + schedule.dueOn()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(outboxEvents).extracting(HouseholdOutboxEvent::getId).contains(expectedDetId);

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

        List<DeviceResponse> devices = service.devicesPage(buyer, null, 1, 20).items();
        assertThat(devices).hasSize(1);
        DeviceResponse autoDevice = devices.getFirst();
        assertThat(autoDevice.deviceType()).isEqualTo("WATER_PURIFIER");

        List<WarrantyResponse> warranties = service.listWarranties(buyer, autoDevice.id());
        assertThat(warranties).hasSize(1);
        WarrantyResponse autoWarranty = warranties.getFirst();
        assertThat(autoWarranty.source()).isEqualTo("ORDER_AUTO_ACTIVATED");
        assertThat(autoWarranty.organizationId()).isEqualTo(orgId);
        assertThat(autoWarranty.status()).isEqualTo(EntityStatus.ACTIVE.name());

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

        assertThatThrownBy(() -> service.warrantiesPage("spoofing-org-user", orgId, false, true, 1, 20))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        when(providerOrgClient.verifyOrganizationMembership(orgId, "valid-org-user")).thenReturn(true);
        assertThat(service.warrantiesPage("valid-org-user", orgId, false, true, 1, 20).items()).hasSize(1);
    }

    @Test
    void preventsWarrantyForgeryProtectsStoreWarrantiesAndAllowsOrgOperatorPersonalWarranties() {
        String owner = "kc-warranty-security-user";
        UUID fakeOrgId = UUID.randomUUID();
        var household = service.createHousehold(owner, new CreateHouseholdRequest("Home", "Address", null));
        var device = service.createDevice(owner, new CreateDeviceRequest(household.id(), "AC", "AC", "Daikin", "M1", null, LocalDate.now()));

        WarrantyResponse manualWarranty = service.createWarranty(
                owner,
                device.id(),
                new CreateWarrantyRequest("Manual", LocalDate.now(), LocalDate.now().plusYears(1), null, null, fakeOrgId));
        assertThat(manualWarranty.organizationId()).isNull();
        assertThat(manualWarranty.source()).isEqualTo("MANUAL");

        VerifyWarrantyResponse eligibility = service.verifyWarrantyEligibility(manualWarranty.id(), owner);
        assertThat(eligibility.valid()).isFalse();
        assertThat(eligibility.expired()).isFalse();
        assertThat(eligibility.status()).isEqualTo("NOT_STORE_WARRANTY");

        // Org operator without org scope sees their own personal warranties
        var personalPage = service.warrantiesPage(owner, null, false, true, 1, 20);
        assertThat(personalPage.items()).extracting(WarrantyResponse::id).contains(manualWarranty.id());

        // Auto-activated store warranty cannot be updated or deleted by owner
        UUID storeOrgId = UUID.randomUUID();
        String deliveredEventJson = """
                {"eventId":"%s","eventType":"order.line.delivered.v1","data":{"orderId":"%s","orderLineId":"%s","buyerProfileId":"%s","organizationId":"%s","merchantName":"Store X","productId":"%s","productName":"Filter","category":"WATER_PURIFIER","brand":"B","productType":"MAJOR_PART","warrantyMonths":12,"returnWindowDays":15}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), owner, storeOrgId, UUID.randomUUID()).strip();
        eventConsumer.onDomainEvent(deliveredEventJson);

        WarrantyResponse storeWarranty = service.listWarranties(owner, service.devicesPage(owner, null, 1, 20).items().stream()
                        .filter(d -> d.deviceType().equals("WATER_PURIFIER")).findFirst().orElseThrow().id())
                .getFirst();
        assertThatThrownBy(() -> service.updateWarranty(
                owner,
                storeWarranty.id(),
                new UpdateWarrantyRequest("Tampered", null, LocalDate.now().plusYears(10), null, null, null)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");
        assertThatThrownBy(() -> service.deleteWarranty(owner, storeWarranty.id()))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");
        assertThatThrownBy(() -> service.deleteDevice(owner, storeWarranty.deviceId()))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("OPERATION_FORBIDDEN");
    }

    @Test
    void rejectsOrgHeaderOnPersonalHouseholdsAndScopesOrgWarrantiesStrictly() throws Exception {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        String buyer = "buyer-w-" + UUID.randomUUID();
        eventConsumer.onDomainEvent("""
                {"eventId":"%s","eventType":"order.line.delivered.v1","data":{"orderId":"%s","orderLineId":"%s","buyerProfileId":"%s","organizationId":"%s","merchantName":"Store A","productId":"%s","productName":"AC","category":"AC","brand":"B","productType":"MAJOR_PART","warrantyMonths":12,"returnWindowDays":15}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), buyer, orgA, UUID.randomUUID()).strip());

        var mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Personal households and devices reject X-Organization-Id with 403
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/households")
                        .header("X-Organization-Id", orgA)
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(buyer))))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/devices")
                        .header("X-Organization-Id", orgA)
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(buyer))))
                .andExpect(MockMvcResultMatchers.status().isForbidden());

        // ORG_TECHNICIAN without warranty:read cannot list org warranties
        when(providerOrgClient.verify(orgA, "tech-only"))
                .thenReturn(VerifiedMembership.of(orgA, "tech-only", "ORG_TECHNICIAN", true));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/warranties")
                        .header("X-Organization-Id", orgA)
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("tech-only"))))
                .andExpect(MockMvcResultMatchers.status().isForbidden());

        // ORG_STAFF with warranty:read sees OrgA warranties and is blocked on mismatched ?organizationId=OrgB
        when(providerOrgClient.verify(orgA, "staff-a"))
                .thenReturn(VerifiedMembership.of(orgA, "staff-a", "ORG_STAFF", true));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/warranties")
                        .header("X-Organization-Id", orgA)
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("staff-a"))))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.pagination.totalItems").value(1));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/warranties?organizationId=" + orgB)
                        .header("X-Organization-Id", orgA)
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("staff-a"))))
                .andExpect(MockMvcResultMatchers.status().isForbidden());

        // Global admin without membership in OrgA is blocked in ORGANIZATION mode, but allowed via PERSONAL mode ?organizationId=
        when(providerOrgClient.verify(orgA, "global-admin"))
                .thenReturn(VerifiedMembership.of(orgA, "global-admin", null, false));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/warranties")
                        .header("X-Organization-Id", orgA)
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("global-admin").claim("user_roles", List.of("ADMIN")))))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/warranties?organizationId=" + orgA)
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("global-admin").claim("user_roles", List.of("ADMIN")))))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.pagination.totalItems").value(1));
    }

    @Test
    void householdsAndDevicesPaginateAtDatabaseLevel() {
        String user = "paged-hh-" + UUID.randomUUID();
        var h1 = service.createHousehold(user, new CreateHouseholdRequest("Home 1", "Addr 1", null));
        service.createHousehold(user, new CreateHouseholdRequest("Home 2", "Addr 2", null));
        service.createDevice(user, new CreateDeviceRequest(h1.id(), "D1", "AC", "B", "M1", null, LocalDate.now()));
        service.createDevice(user, new CreateDeviceRequest(h1.id(), "D2", "AC", "B", "M2", null, LocalDate.now()));

        var hPage = service.householdsPage(user, 1, 1);
        assertThat(hPage.items()).hasSize(1);
        assertThat(hPage.pagination().totalItems()).isEqualTo(2);

        var dPage = service.devicesPage(user, null, 1, 1);
        assertThat(dPage.items()).hasSize(1);
        assertThat(dPage.pagination().totalItems()).isEqualTo(2);
    }
}
